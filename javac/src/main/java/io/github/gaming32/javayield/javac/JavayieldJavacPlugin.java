package io.github.gaming32.javayield.javac;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ListIterator;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import io.github.gaming32.javayield.transform.YieldTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;

public class JavayieldJavacPlugin implements Plugin {
    public static JavaFileObject lastClassFile;

    @Override
    public String getName() {
        return "javayield";
    }

    @Override
    public void init(JavacTask task, String... args) {
        final Instrumentation inst = ByteBuddyAgent.install();
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                if (!className.equals("com/sun/tools/javac/main/JavaCompiler")) {
                    return null;
                }
                final ClassNode clazz = new ClassNode();
                new ClassReader(classfileBuffer).accept(clazz, 0);
                for (final MethodNode method : clazz.methods) {
                    if (
                        method.name.equals("generate") &&
                        method.desc.equals("(Ljava/util/Queue;Ljava/util/Queue;)V")
                    ) {
                        final ListIterator<AbstractInsnNode> it = method.instructions.iterator();
                        while (it.hasNext()) {
                            final AbstractInsnNode insn = it.next();
                            if (insn instanceof MethodInsnNode) {
                                final MethodInsnNode methodInsn = (MethodInsnNode)insn;
                                if (
                                    methodInsn.owner.equals("com/sun/tools/javac/main/JavaCompiler") &&
                                    methodInsn.name.equals("genCode") &&
                                    methodInsn.desc.equals("(Lcom/sun/tools/javac/comp/Env;Lcom/sun/tools/javac/tree/JCTree$JCClassDecl;)Ljavax/tools/JavaFileObject;")
                                ) {
                                    it.add(new InsnNode(Opcodes.DUP));
                                    it.add(new FieldInsnNode(
                                        Opcodes.PUTSTATIC,
                                        "io/github/gaming32/javayield/javac/JavayieldJavacPlugin",
                                        "lastClassFile",
                                        "Ljavax/tools/JavaFileObject;"
                                    ));
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
                final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                clazz.accept(writer);
                return writer.toByteArray();
            }
        }, true);
        try {
            inst.retransformClasses(Class.forName("com.sun.tools.javac.main.JavaCompiler"));
        } catch (Exception e) {
            throw new Error(e);
        }

        final Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.GENERATE) return;
                final byte[] input;
                try (InputStream is = lastClassFile.openInputStream()) {
                    input = is.readAllBytes();
                } catch (Exception e1) {
                    trees.printMessage(
                        Diagnostic.Kind.ERROR, "Failed to read class file: " + e,
                        e.getCompilationUnit(), e.getCompilationUnit()
                    );
                    return;
                }
                final byte[] output = YieldTransformer.transformClass(input);
                if (output != null) {
                    try (OutputStream os = lastClassFile.openOutputStream()) {
                        os.write(output);
                    } catch (Exception e1) {
                        trees.printMessage(
                            Diagnostic.Kind.ERROR, "Failed to write class file: " + e,
                            e.getCompilationUnit(), e.getCompilationUnit()
                        );
                        return;
                    }
                }
            }
        });
    }
}