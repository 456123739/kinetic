package com.kinetic.client.compat.tacz;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 让单测能把**发布版** TaCZ jar 加载进开发环境。
 *
 * <p>发布 jar 里的 {@code net/minecraft} 成员是 SRG 名（{@code m_253071_}），而开发环境的
 * Minecraft 是命名映射（{@code step}）。两者类名相同、成员名不同，所以只重写方法/字段指令，
 * 不动类结构。映射直接读构建产物 {@code intermediateToNamed.srg}，不手写、不猜。
 *
 * <p>只用于测试：这里不做验签、不做缓存，生产路径永远用真实 jar。
 */
final class TaczDevRemapper extends ClassLoader {
    private static final Map<String, String> CLASSES = new HashMap<>();
    private static final Map<String, String> MEMBERS = new HashMap<>();

    static {
        var path = Path.of("build", "moddev", "artifacts", "intermediateToNamed.srg");
        try {
            for (var line : Files.readAllLines(path)) {
                if (line.startsWith("CL: ")) {
                    var parts = line.substring(4).split(" ");
                    if (parts.length == 2) {
                        CLASSES.put(parts[0], parts[1]);
                    }
                } else if (line.startsWith("MD: ")) {
                    // MD: owner/name (desc) target/name (desc)
                    var parts = line.substring(4).split(" ");
                    if (parts.length >= 4) {
                        MEMBERS.put(parts[0] + parts[1], parts[2]);
                    }
                } else if (line.startsWith("FD: ")) {
                    // FD: owner/name target/name
                    // 这些行可能带 " # 描述" 尾注，取前两列即可。
                    var parts = line.substring(4).split(" ");
                    if (parts.length >= 2) {
                        MEMBERS.put(parts[0], parts[1]);
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Mapping file is unavailable: " + path, error);
        }
    }

    TaczDevRemapper(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 纯数据 POJO 留在父加载器：Gson 也要用同一份类，否则反序列化出来的是另一个类型。
        if (!name.startsWith("com.tacz.") || name.startsWith("com.tacz.guns.client.resource.pojo.")) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = transform(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> transform(String name) throws ClassNotFoundException {
        byte[] bytes;
        try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) {
                throw new ClassNotFoundException(name);
            }
            bytes = input.readAllBytes();
        } catch (IOException error) {
            throw new ClassNotFoundException(name, error);
        }
        var writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, methodName, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        var remapped = MEMBERS.get(owner + "/" + method + desc);
                        if (remapped == null) {
                            super.visitMethodInsn(opcode, remapClass(owner), method, desc, isInterface);
                        } else {
                            var slash = remapped.lastIndexOf('/');
                            super.visitMethodInsn(opcode,
                                    remapClass(remapped.substring(0, slash)),
                                    remapped.substring(slash + 1), desc, isInterface);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        var remapped = MEMBERS.get(owner + "/" + field);
                        if (remapped == null) {
                            super.visitFieldInsn(opcode, remapClass(owner), field, desc);
                        } else {
                            var slash = remapped.lastIndexOf('/');
                            super.visitFieldInsn(opcode, remapClass(remapped.substring(0, slash)),
                                    remapped.substring(slash + 1), desc);
                        }
                    }
                };
            }
        }, 0);
        var code = writer.toByteArray();
        return defineClass(name, code, 0, code.length);
    }

    private static String remapClass(String owner) {
        var mapped = CLASSES.get(owner);
        return mapped == null ? owner : mapped;
    }
}
