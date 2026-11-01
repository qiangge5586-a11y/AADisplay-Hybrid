package com.diplay.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ManifestPatchPlanTest {
    private static final String ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    @Test public void createsDryRunWithoutChangingSourceTree() {
        BinaryXmlManifestParser.Document doc = sampleDocument();
        ManifestPatchPlan plan = ManifestPatchPlan.forDocument(doc)
                .replaceExistingAndroidString("/manifest/application", "label",
                        "DiPlay 测试标签", "验证 dry-run 流程")
                .build();

        assertEquals("com.example.demo", plan.getPackageName());
        assertEquals(1, plan.getChanges().size());
        assertEquals("旧标签", doc.findFirst("application").attribute("label"));
        assertTrue(plan.toDryRunReport().contains("仅预览"));
        assertTrue(plan.toDryRunReport().contains("旧标签 -> DiPlay 测试标签"));
    }

    @Test public void rejectsSecuritySensitiveAttributes() {
        try {
            ManifestPatchPlan.forDocument(sampleDocument())
                    .replaceExistingAndroidString("/manifest/application", "debuggable",
                            "true", "test");
            fail("Expected protected attribute rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("安全敏感"));
        }
    }

    @Test public void rejectsMissingAttributeInsteadOfSilentlyAddingIt() {
        try {
            ManifestPatchPlan.forDocument(sampleDocument())
                    .replaceExistingAndroidString("/manifest/application", "theme",
                            "@style/AppTheme", "test");
            fail("Expected missing attribute rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不支持新增属性"));
        }
    }

    private static BinaryXmlManifestParser.Document sampleDocument() {
        BinaryXmlManifestParser.Element manifest =
                new BinaryXmlManifestParser.Element(null, "manifest");
        manifest.attributes.add(new BinaryXmlManifestParser.Attribute(
                null, "package", "com.example.demo", 0x03, 0));
        BinaryXmlManifestParser.Element app =
                new BinaryXmlManifestParser.Element(null, "application");
        app.attributes.add(new BinaryXmlManifestParser.Attribute(
                ANDROID_NS, "label", "旧标签", 0x03, 0));
        manifest.children.add(app);
        List<BinaryXmlManifestParser.Element> roots = new ArrayList<>();
        roots.add(manifest);
        return new BinaryXmlManifestParser.Document(roots);
    }
}
