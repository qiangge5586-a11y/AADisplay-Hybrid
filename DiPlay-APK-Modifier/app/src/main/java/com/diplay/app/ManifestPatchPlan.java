package com.diplay.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable dry-run description of proposed Manifest edits.
 *
 * This class deliberately does not serialize AXML or modify an APK. It is the
 * validation boundary between analysis and a future, separately tested writer.
 */
public final class ManifestPatchPlan {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private final String packageName;
    private final List<Change> changes;

    private ManifestPatchPlan(String packageName, List<Change> changes) {
        this.packageName = packageName;
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
    }

    public String getPackageName() { return packageName; }
    public List<Change> getChanges() { return changes; }
    public boolean isEmpty() { return changes.isEmpty(); }

    public String toDryRunReport() {
        StringBuilder out = new StringBuilder();
        out.append("DiPlay Manifest 修改计划（仅预览，不会写入 APK）\n");
        out.append("目标包名：").append(packageName).append("\n");
        out.append("变更数量：").append(changes.size()).append("\n");
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            out.append(i + 1).append(". ").append(c.elementPath)
                    .append(" @android:").append(c.attributeName)
                    .append("：").append(c.oldValue)
                    .append(" -> ").append(c.newValue)
                    .append("\n理由：").append(c.reason).append("\n");
        }
        out.append("安全边界：本计划未修改二进制 Manifest、DEX、资源或 APK 签名。\n");
        return out.toString();
    }

    public static Builder forDocument(BinaryXmlManifestParser.Document document) {
        if (document == null) throw new IllegalArgumentException("Manifest 文档不能为空");
        BinaryXmlManifestParser.Element manifest = document.findFirst("manifest");
        if (manifest == null) throw new IllegalArgumentException("Manifest 根节点不存在");
        String packageName = manifest.attribute("package");
        if (packageName == null || packageName.trim().isEmpty())
            throw new IllegalArgumentException("Manifest 缺少 package");
        return new Builder(document, packageName);
    }

    public static final class Builder {
        private final BinaryXmlManifestParser.Document document;
        private final String packageName;
        private final List<Change> changes = new ArrayList<>();

        private Builder(BinaryXmlManifestParser.Document document, String packageName) {
            this.document = document;
            this.packageName = packageName;
        }

        /**
         * Plans replacement of an existing android-namespaced string attribute.
         * Adding attributes and changing security/identity fields are intentionally
         * unsupported until the AXML writer and policy checks are independently verified.
         */
        public Builder replaceExistingAndroidString(String elementPath, String attributeName,
                                                     String newValue, String reason) {
            if (elementPath == null || !elementPath.startsWith("/manifest"))
                throw new IllegalArgumentException("目标路径必须从 /manifest 开始");
            if (attributeName == null || attributeName.trim().isEmpty())
                throw new IllegalArgumentException("属性名称不能为空");
            if (newValue == null) throw new IllegalArgumentException("新值不能为空");
            if (reason == null || reason.trim().isEmpty())
                throw new IllegalArgumentException("必须说明修改理由");
            if (isProtected(attributeName))
                throw new IllegalArgumentException("禁止通过计划修改身份或安全敏感属性：" + attributeName);

            BinaryXmlManifestParser.Element target = resolveUnique(elementPath);
            BinaryXmlManifestParser.Attribute found = null;
            for (BinaryXmlManifestParser.Attribute attribute : target.attributes) {
                if (attributeName.equals(attribute.name) && ANDROID_NS.equals(attribute.namespace)) {
                    found = attribute;
                    break;
                }
            }
            if (found == null)
                throw new IllegalArgumentException("目标节点不存在该 android 属性；当前版本不支持新增属性");
            if (found.valueType != 0x03)
                throw new IllegalArgumentException("当前版本只允许替换已有字符串类型属性");
            if (found.value.equals(newValue))
                throw new IllegalArgumentException("新旧值相同，无需生成变更");

            changes.add(new Change(elementPath, attributeName, found.value, newValue, reason));
            return this;
        }

        public ManifestPatchPlan build() {
            return new ManifestPatchPlan(packageName, changes);
        }

        private BinaryXmlManifestParser.Element resolveUnique(String path) {
            String[] parts = path.split("/");
            List<BinaryXmlManifestParser.Element> current = document.roots;
            BinaryXmlManifestParser.Element found = null;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                String name = part;
                int wanted = -1;
                int bracket = part.lastIndexOf('[');
                if (bracket > 0 && part.endsWith("]")) {
                    name = part.substring(0, bracket);
                    try { wanted = Integer.parseInt(part.substring(bracket + 1, part.length() - 1)); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("路径索引无效：" + part); }
                    if (wanted < 0) throw new IllegalArgumentException("路径索引不能为负数");
                }
                List<BinaryXmlManifestParser.Element> matches = new ArrayList<>();
                for (BinaryXmlManifestParser.Element element : current)
                    if (name.equals(element.name)) matches.add(element);
                if (wanted >= 0) {
                    if (wanted >= matches.size()) throw new IllegalArgumentException("路径节点不存在：" + part);
                    found = matches.get(wanted);
                } else {
                    if (matches.size() != 1)
                        throw new IllegalArgumentException(matches.isEmpty()
                                ? "路径节点不存在：" + part : "路径不唯一，请使用 [索引]：" + part);
                    found = matches.get(0);
                }
                current = found.children;
            }
            if (found == null) throw new IllegalArgumentException("目标路径为空");
            return found;
        }

        private static boolean isProtected(String name) {
            return "name".equals(name) || "exported".equals(name) ||
                    "permission".equals(name) || "process".equals(name) ||
                    "debuggable".equals(name) || "testOnly".equals(name) ||
                    "sharedUserId".equals(name);
        }
    }

    public static final class Change {
        public final String elementPath;
        public final String attributeName;
        public final String oldValue;
        public final String newValue;
        public final String reason;

        private Change(String elementPath, String attributeName, String oldValue,
                       String newValue, String reason) {
            this.elementPath = elementPath;
            this.attributeName = attributeName;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.reason = reason;
        }
    }
}
