/**
 * Legacy 容器的 JNI 兼容垫片。
 *
 * <p><b>这些类不再参与任何模型加载路径</b>：{@code ModelSourceResolver} 对
 * {@code ModelSourceKind.LEGACY_ARCHIVE} 直接抛错，因此 v3 加密容器永远不会被解密、
 * 也不会被当作明文误读。
 *
 * <p>保留它们的唯一目的是 <b>加载期兼容</b>：native 库在 {@code JNI_OnLoad} 时按类名与
 * 方法签名强制绑定，缺少任何一个类都会导致整库加载失败。这样 Java 侧可以同时兼容两种
 * native 产物：
 * <ul>
 *   <li>从本仓库裁剪后的 native 源码构建的库（不含 legacy 绑定）；</li>
 *   <li>从上游未修改的 native 源码构建的库（含 legacy 绑定，例如 Windows 上直接
 *       {@code git clone} 上游仓库构建出的 {@code kinetic.dll}）。</li>
 * </ul>
 *
 * <p>如果将来只使用裁剪过的 native 产物，可以整体删除本包以缩减体积。
 */
package com.kinetic.natives.legacy;
