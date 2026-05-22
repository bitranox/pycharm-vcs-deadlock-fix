package dev.bx.pycharm;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Java agent that defuses the IntelliJ Platform VCS-root explosion deadlock
 * by neutralizing a specific runBlockingMaybeCancellable call in
 * GitRepositoryImpl's constructor.
 *
 * Target: git4idea.repo.GitRepositoryImpl.<init>
 *
 * Stock IntelliJ behaviour: the constructor calls
 *   runBlockingMaybeCancellable { workingTreeHolder.updateState() }
 * which blocks the constructing thread on a suspending function that
 * itself acquires further locks and (possibly) runs a `git` subprocess.
 * When the constructor is invoked from
 *   VcsRepositoryManager.findNewRoots()
 * while the global MODIFY_LOCK is held, each per-repo construction
 * extends the lock-hold time by the time of the suspend chain. With
 * N nested git repos in a single project, the lock is held for N × that
 * cost — minutes of IDE freeze.
 *
 * Patched behaviour: the constructor's call to runBlockingMaybeCancellable
 * is rewritten in-place to discard the Function2 (the suspend lambda)
 * and push a null result, skipping the synchronous wait entirely. The
 * GitRepositoryImpl object is left with workingTreeHolder in its initial
 * (empty) state; the first real updateState() happens later when
 * something queries the repo. By then it runs on its own coroutine
 * dispatcher, NOT under MODIFY_LOCK.
 *
 * Effects:
 *   - findNewRoots() can iterate any number of candidates and construct
 *     all their GitRepositoryImpls in milliseconds; MODIFY_LOCK is held
 *     only as long as the trivial book-keeping in findNewRoots takes.
 *   - Initial repo state (branch, HEAD, working-tree info) is populated
 *     asynchronously, exactly the same way it gets re-populated after
 *     any later refresh. No queries that I am aware of *require* the
 *     state to exist synchronously at end-of-constructor; the very
 *     features that consume it (gutter, annotations, log) all subscribe
 *     to the state-flow and update reactively.
 *   - No restore needed — the patch is correct for the entire session.
 *     We do not modify findNewRoots, the alarm, or any other path. New
 *     repos are auto-discovered normally.
 *
 * Only one specific INVOKESTATIC inside <init> is touched; calls to the
 * same helper from other methods of GitRepositoryImpl (e.g. the
 * user-initiated update() path) are left alone — they are not called
 * under MODIFY_LOCK and the synchronous behaviour there is desired.
 *
 * Configuration:
 *   -Dvcs.patch.verbose=true   extra logging for each patched call site
 */
public class VcsPatchAgent {
    private static final String TARGET_INTERNAL = "git4idea/repo/GitRepositoryImpl";
    private static final String NEUTRALIZE_OWNER = "com/intellij/openapi/progress/CoroutinesKt";
    private static final String NEUTRALIZE_NAME  = "runBlockingMaybeCancellable";

    private static final boolean VERBOSE = Boolean.getBoolean("vcs.patch.verbose");

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[VcsPatchAgent] premain: target=" + TARGET_INTERNAL
                + ".<init> neutralizing " + NEUTRALIZE_OWNER + "." + NEUTRALIZE_NAME
                + " (always on, no restore needed)");
        inst.addTransformer(new Transformer(), true);
    }

    static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain pd, byte[] classfile) {
            if (!TARGET_INTERNAL.equals(className)) return null;
            if (classBeingRedefined != null) return null;
            try {
                ClassReader cr = new ClassReader(classfile);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                final int[] siteCount = new int[]{ 0 };
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (!"<init>".equals(name)) return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String n, String d, boolean iface) {
                                if (opcode == Opcodes.INVOKESTATIC
                                        && NEUTRALIZE_OWNER.equals(owner)
                                        && NEUTRALIZE_NAME.equals(n)) {
                                    siteCount[0]++;
                                    System.out.println("[VcsPatchAgent] neutralizing " + owner + "." + n + d
                                            + " in " + TARGET_INTERNAL + ".<init>" + " (call site #" + siteCount[0] + ")");
                                    // Stack at this point holds the Function2 we'd pass.
                                    // Discard it, then push null in place of the would-be return value.
                                    super.visitInsn(Opcodes.POP);
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, n, d, iface);
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        if (siteCount[0] == 0) {
                            System.err.println("[VcsPatchAgent] WARNING: no " + NEUTRALIZE_NAME
                                    + " call found in " + TARGET_INTERNAL + ".<init> — patch had no effect");
                        }
                        super.visitEnd();
                    }
                }, 0);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.err.println("[VcsPatchAgent] transform failed for " + className + ": " + t);
                t.printStackTrace();
                return null;
            }
        }
    }
}
