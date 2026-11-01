package com.diplay.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only parser for Android's compiled binary XML format (AXML).
 *
 * This intentionally exposes a small, loss-minimizing tree for diagnostics.
 * It does not rewrite manifests or APK files.
 */
public final class BinaryXmlManifestParser {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int NO_INDEX = 0xffffffff;

    private BinaryXmlManifestParser() {}

    public static Document parse(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input == null");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return parse(out.toByteArray());
    }

    public static Document parse(byte[] data) throws IOException {
        if (data == null || data.length < 8) throw new IOException("AXML 文件过短");
        int rootType = u16(data, 0);
        int rootHeader = u16(data, 2);
        long declaredSize = u32(data, 4);
        if (rootType != RES_XML_TYPE) throw new IOException("不是 Android 二进制 XML");
        if (rootHeader < 8 || declaredSize > data.length || declaredSize < rootHeader)
            throw new IOException("AXML 根块长度无效");

        List<String> strings = new ArrayList<>();
        List<Element> roots = new ArrayList<>();
        List<Element> stack = new ArrayList<>();
        int offset = rootHeader;
        int limit = (int) declaredSize;

        while (offset + 8 <= limit) {
            int type = u16(data, offset);
            int headerSize = u16(data, offset + 2);
            long chunkSizeLong = u32(data, offset + 4);
            if (headerSize < 8 || chunkSizeLong < headerSize || chunkSizeLong > limit - offset)
                throw new IOException("AXML chunk 长度异常，offset=" + offset);
            int chunkSize = (int) chunkSizeLong;
            int end = offset + chunkSize;

            if (type == RES_STRING_POOL_TYPE) {
                strings = readStringPool(data, offset, headerSize, chunkSize);
            } else if (type == RES_XML_START_ELEMENT_TYPE) {
                Element element = readStartElement(data, offset, headerSize, chunkSize, strings);
                if (stack.isEmpty()) roots.add(element);
                else stack.get(stack.size() - 1).children.add(element);
                stack.add(element);
            } else if (type == RES_XML_END_ELEMENT_TYPE) {
                if (stack.isEmpty()) throw new IOException("AXML 元素结束标签不匹配");
                stack.remove(stack.size() - 1);
            } else if (type == RES_XML_CDATA_TYPE) {
                if (!stack.isEmpty() && offset + 28 <= end) {
                    int textIndex = index(u32(data, offset + 16), strings.size());
                    if (textIndex >= 0) stack.get(stack.size() - 1).text = strings.get(textIndex);
                }
            }
            offset = end;
        }
        if (offset != limit) throw new IOException("AXML 尾部存在不完整 chunk");
        if (!stack.isEmpty()) throw new IOException("AXML 元素未正常闭合");
        return new Document(roots);
    }

    private static Element readStartElement(byte[] b, int off, int header, int size,
                                            List<String> strings) throws IOException {
        if (header < 16 || size < 36) throw new IOException("AXML start-element 块过短");
        int ext = off + header;
        int nsIndex = index(u32(b, ext), strings.size());
        int nameIndex = index(u32(b, ext + 4), strings.size());
        if (nameIndex < 0) throw new IOException("AXML 元素名称索引无效");
        int attributeStart = u16(b, ext + 8);
        int attributeSize = u16(b, ext + 10);
        int attributeCount = u16(b, ext + 12);
        if (attributeSize < 20) throw new IOException("AXML 属性记录长度无效");
        long first = (long) ext + attributeStart;
        long last = first + (long) attributeCount * attributeSize;
        if (first < ext || last > (long) off + size) throw new IOException("AXML 属性越界");

        Element element = new Element(stringAt(strings, nsIndex), strings.get(nameIndex));
        int p = (int) first;
        for (int i = 0; i < attributeCount; i++, p += attributeSize) {
            int attrNs = index(u32(b, p), strings.size());
            int attrName = index(u32(b, p + 4), strings.size());
            int raw = index(u32(b, p + 8), strings.size());
            if (attrName < 0) continue;
            int valueType = b[p + 15] & 0xff;
            long valueData = u32(b, p + 16);
            String value;
            if (raw >= 0) value = strings.get(raw);
            else if (valueType == TYPE_STRING) value = stringAt(strings, index(valueData, strings.size()));
            else if (valueType == TYPE_INT_BOOLEAN) value = valueData != 0 ? "true" : "false";
            else if (valueType == TYPE_INT_HEX) value = "0x" + Long.toHexString(valueData);
            else if (valueType == TYPE_INT_DEC) value = Long.toString(valueData);
            else value = "type=0x" + Integer.toHexString(valueType) + ",data=0x" + Long.toHexString(valueData);
            element.attributes.add(new Attribute(stringAt(strings, attrNs), strings.get(attrName), value, valueType, valueData));
        }
        return element;
    }

    private static List<String> readStringPool(byte[] b, int off, int header, int size) throws IOException {
        if (header < 28 || size < header) throw new IOException("AXML 字符串池过短");
        int count = checkedInt(u32(b, off + 8), "stringCount");
        int flags = (int) u32(b, off + 16);
        int stringsStart = checkedInt(u32(b, off + 20), "stringsStart");
        long offsetsEnd = (long) off + header + (long) count * 4;
        if (offsetsEnd > (long) off + size || stringsStart < header || stringsStart > size)
            throw new IOException("AXML 字符串池偏移越界");
        boolean utf8 = (flags & 0x100) != 0;
        List<String> result = new ArrayList<>(count);
        int offsets = off + header;
        int base = off + stringsStart;
        for (int i = 0; i < count; i++) {
            long rel = u32(b, offsets + i * 4);
            long posLong = (long) base + rel;
            if (posLong < base || posLong >= (long) off + size) throw new IOException("字符串偏移越界");
            int pos = (int) posLong;
            if (utf8) {
                int[] a = readLength8(b, pos, off + size);
                int[] c = readLength8(b, a[1], off + size);
                if ((long)c[1] + c[0] > (long)off + size) throw new IOException("UTF-8 字符串越界");
                result.add(new String(b, c[1], c[0], StandardCharsets.UTF_8));
            } else {
                int[] a = readLength16(b, pos, off + size);
                long bytes = (long)a[0] * 2;
                if ((long)a[1] + bytes > (long)off + size) throw new IOException("UTF-16 字符串越界");
                result.add(new String(b, a[1], (int)bytes, StandardCharsets.UTF_16LE));
            }
        }
        return result;
    }

    private static int[] readLength8(byte[] b, int p, int limit) throws IOException {
        if (p >= limit) throw new IOException("字符串长度字段越界");
        int first = b[p++] & 0xff;
        if ((first & 0x80) == 0) return new int[]{first, p};
        if (p >= limit) throw new IOException("字符串长度字段不完整");
        return new int[]{((first & 0x7f) << 8) | (b[p++] & 0xff), p};
    }

    private static int[] readLength16(byte[] b, int p, int limit) throws IOException {
        if (p + 2 > limit) throw new IOException("UTF-16 长度字段越界");
        int first = u16(b, p); p += 2;
        if ((first & 0x8000) == 0) return new int[]{first, p};
        if (p + 2 > limit) throw new IOException("UTF-16 长度字段不完整");
        return new int[]{((first & 0x7fff) << 16) | u16(b, p), p + 2};
    }

    private static int checkedInt(long value, String name) throws IOException {
        if (value > Integer.MAX_VALUE) throw new IOException(name + " 超出范围");
        return (int)value;
    }
    private static int index(long value, int count) {
        return value == NO_INDEX || value >= count ? -1 : (int)value;
    }
    private static String stringAt(List<String> values, int index) {
        return index < 0 || index >= values.size() ? null : values.get(index);
    }
    private static int u16(byte[] b, int p) throws IOException {
        if (p < 0 || p + 2 > b.length) throw new IOException("AXML 读取越界");
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }
    private static long u32(byte[] b, int p) throws IOException {
        if (p < 0 || p + 4 > b.length) throw new IOException("AXML 读取越界");
        return ((long)b[p] & 255) | (((long)b[p + 1] & 255) << 8)
                | (((long)b[p + 2] & 255) << 16) | (((long)b[p + 3] & 255) << 24);
    }

    public static final class Document {
        public final List<Element> roots;
        Document(List<Element> roots) { this.roots = Collections.unmodifiableList(roots); }
        public Element findFirst(String name) {
            for (Element root : roots) {
                Element found = find(root, name);
                if (found != null) return found;
            }
            return null;
        }
        private static Element find(Element e, String name) {
            if (name.equals(e.name)) return e;
            for (Element child : e.children) {
                Element found = find(child, name);
                if (found != null) return found;
            }
            return null;
        }
    }

    public static final class Element {
        public final String namespace;
        public final String name;
        public String text;
        public final List<Attribute> attributes = new ArrayList<>();
        public final List<Element> children = new ArrayList<>();
        Element(String namespace, String name) { this.namespace = namespace; this.name = name; }
        public String attribute(String name) {
            for (Attribute a : attributes) if (name.equals(a.name)) return a.value;
            return null;
        }
    }

    public static final class Attribute {
        public final String namespace;
        public final String name;
        public final String value;
        public final int valueType;
        public final long valueData;
        Attribute(String namespace, String name, String value, int valueType, long valueData) {
            this.namespace = namespace; this.name = name; this.value = value;
            this.valueType = valueType; this.valueData = valueData;
        }
    }
}
