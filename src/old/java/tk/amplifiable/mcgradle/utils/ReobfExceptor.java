package club.ampthedev.mcgradle.utils;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import de.oceanlabs.mcp.mcinjector.StringUtil;
import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ReobfExceptor {
    public File toReobfJar;
    public File deobfJar;
    public File methodCSV;
    public File fieldCSV;
    public File excConfig;
    Map<String, String> clsMap = Maps.newHashMap();
    Map<String, String> access = Maps.newHashMap();


    public void buildSrg(File inSrg, File outSrg) throws IOException {
        if (outSrg.isFile())
            outSrg.delete();

        String fixed = Files.asCharSource(inSrg, Charset.defaultCharset()).readLines(new SrgLineProcessor(clsMap, access));
        Files.write(fixed.getBytes(), outSrg);
    }

    public void doFirstThings() throws IOException {
        Map<String, String> csvData = readCSVs();
        JarInfo oldInfo = readJar(deobfJar);
        JarInfo newInfo = readJar(toReobfJar);

        clsMap = createClassMap(newInfo.map, newInfo.interfaces);
        renameAccess(oldInfo.access, csvData);
        access = mergeAccess(newInfo.access, oldInfo.access);
    }

    private Map<String, String> readCSVs() throws IOException {
        final Map<String, String> csvData = Maps.newHashMap();
        File[] csvs = new File[]
                {
                        fieldCSV,
                        methodCSV
                };

        for (File f : csvs) {
            if (f == null) continue;

            Files.asCharSource(f, Charset.defaultCharset()).readLines(new LineProcessor<Object>() {
                @Override
                public boolean processLine(String line) {
                    String[] s = line.split(",");
                    csvData.put(s[0], s[1]);
                    return true;
                }

                @Override
                public Object getResult() {
                    return null;
                }
            });
        }

        return csvData;
    }

    private void renameAccess(Map<String, AccessInfo> data, Map<String, String> csvData) {
        for (AccessInfo info : data.values()) {
            for (Insn i : info.insns) {
                String tmp = csvData.get(i.name);
                i.name = tmp == null ? i.name : tmp;
            }
        }
    }

    private JarInfo readJar(File inJar) throws IOException {
        ZipInputStream zip = null;
        try {
            try {
                zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(inJar)));
            } catch (FileNotFoundException e) {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            JarInfo reader = new JarInfo();
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                if (entry.isDirectory() ||
                        !entry.getName().endsWith(".class")) continue;
                (new ClassReader(ByteStreams.toByteArray(zip))).accept(reader, 0);
            }
            return reader;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private Map<String, String> createClassMap(Map<String, String> markerMap, final List<String> interfaces) throws IOException {
        Map<String, String> excMap = Files.asCharSource(excConfig, Charset.defaultCharset()).readLines(new LineProcessor<Map<String, String>>() {
            Map<String, String> tmp = Maps.newHashMap();

            @Override
            public boolean processLine(String line) throws IOException {
                if (line.contains(".") ||
                        !line.contains("=") ||
                        line.startsWith("#")) return true;

                String[] s = line.split("=");
                if (!interfaces.contains(s[0])) tmp.put(s[0], s[1] + "_");

                return true;
            }

            @Override
            public Map<String, String> getResult() {
                return tmp;
            }
        });

        Map<String, String> map = Maps.newHashMap();
        for (Map.Entry<String, String> e : excMap.entrySet()) {
            String renamed = markerMap.get(e.getValue());
            if (renamed != null) {
                map.put(e.getKey(), renamed);
            }
        }
        return map;
    }

    private Map<String, String> mergeAccess(Map<String, AccessInfo> old_data, Map<String, AccessInfo> new_data) {
        Iterator<Map.Entry<String, AccessInfo>> itr = old_data.entrySet().iterator();
        while (itr.hasNext()) {
            Map.Entry<String, AccessInfo> e = itr.next();
            String key = e.getKey();
            AccessInfo n = new_data.get(key);
            if (n != null && e.getValue().targetEquals(n)) {
                itr.remove();
                new_data.remove(key);
            }
        }

        Map<String, String> matched = Maps.newHashMap();

        itr = old_data.entrySet().iterator();
        while (itr.hasNext()) {
            AccessInfo _old = itr.next().getValue();
            Iterator<Map.Entry<String, AccessInfo>> itr2 = new_data.entrySet().iterator();
            while (itr2.hasNext()) {
                Map.Entry<String, AccessInfo> e2 = itr2.next();
                AccessInfo _new = e2.getValue();
                if (_old.targetEquals(_new) &&
                        _old.owner.equals(_new.owner) &&
                        _old.desc.equals(_new.desc)) {
                    matched.put(_old.owner + "/" + _old.name, _new.owner + "/" + _new.name);
                    itr.remove();
                    itr2.remove();
                    break;
                }
            }
        }

        return matched;
    }

    private static class SrgLineProcessor implements LineProcessor<String> {
        Map<String, String> map;
        Map<String, String> access;
        StringBuilder out = new StringBuilder();
        Pattern reg = Pattern.compile("L([^;]+);");

        private SrgLineProcessor(Map<String, String> map, Map<String, String> access) {
            this.map = map;
            this.access = access;
        }

        private String rename(String cls) {
            String rename = map.get(cls);
            return rename == null ? cls : rename;
        }

        private String[] rsplit(String value, String delim) {
            int idx = value.lastIndexOf(delim);
            return new String[]
                    {
                            value.substring(0, idx),
                            value.substring(idx + 1)
                    };
        }

        @Override
        public boolean processLine(String line) {
            String[] split = line.split(" ");
            if (split[0].equals("CL:")) {
                split[2] = rename(split[2]);
            } else if (split[0].equals("FD:")) {
                String[] s = rsplit(split[2], "/");
                split[2] = rename(s[0]) + "/" + s[1];
            } else if (split[0].equals("MD:")) {
                String[] s = rsplit(split[3], "/");
                split[3] = rename(s[0]) + "/" + s[1];

                if (access.containsKey(split[3])) {
                    split[3] = access.get(split[3]);
                }

                Matcher m = reg.matcher(split[4]);
                StringBuffer b = new StringBuffer();
                while (m.find()) {
                    m.appendReplacement(b, "L" + rename(m.group(1)).replace("$", "\\$") + ";");
                }
                m.appendTail(b);
                split[4] = b.toString();
            }
            out.append(StringUtil.joinString(Arrays.asList(split), " ")).append('\n');
            return true;
        }

        @Override
        public String getResult() {
            return out.toString();
        }

    }

    private static class JarInfo extends ClassVisitor {
        private final Map<String, String> map = Maps.newHashMap();
        private final List<String> interfaces = Lists.newArrayList();
        private final Map<String, AccessInfo> access = Maps.newHashMap();

        public JarInfo() {
            super(Opcodes.ASM7, null);
        }

        private String className;

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] ints) {
            this.className = name;
            if ((access & Opcodes.ACC_INTERFACE) == Opcodes.ACC_INTERFACE) {
                interfaces.add(className);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            if (name.equals("__OBFID")) {
                if (!className.startsWith("net/minecraft/")) {
                    throw new RuntimeException("Modder stupidity detected, DO NOT USE __OBFID, Copy pasting code you don't understand is bad: " + className);
                }
                map.put(value + "_", className);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
            if (className.startsWith("net/minecraft/") && name.startsWith("access$")) {
                String path = className + "/" + name + desc;
                final AccessInfo info = new AccessInfo(className, name, desc);
                info.access = acc;
                access.put(path, info);

                return new MethodVisitor(Opcodes.ASM7) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        info.add(opcode, owner, name, desc);
                    }
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        info.add(opcode, owner, name, desc);
                    }
                };
            }
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static class AccessInfo {
        public String owner;
        public String name;
        public String desc;
        public int access;
        public List<Insn> insns = new ArrayList<>();
        private String cache = null;

        public AccessInfo(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        public void add(int opcode, String owner, String name, String desc) {
            insns.add(new Insn(opcode, owner, name, desc));
            cache = null;
        }


        @Override
        public String toString() {
            if (cache == null) {
                if (insns.size() < 1)
                    throw new RuntimeException("Empty instruction");

                cache = "[" + Joiner.on(", ").join(insns) + "]";
            }
            return cache;
        }

        public boolean targetEquals(AccessInfo o) {
            return toString().equals(o.toString());
        }
    }

    private static class Insn {
        public int opcode;
        public String owner;
        public String name;
        public String desc;

        Insn(int opcode, String owner, String name, String desc) {
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        @Override
        public String toString() {
            String op = "UNKNOWN_" + opcode;
            switch (opcode) {
                case Opcodes.GETSTATIC:
                    op = "GETSTATIC";
                    break;
                case Opcodes.PUTSTATIC:
                    op = "PUTSTATIC";
                    break;
                case Opcodes.GETFIELD:
                    op = "GETFIELD";
                    break;
                case Opcodes.PUTFIELD:
                    op = "PUTFIELD";
                    break;
                case Opcodes.INVOKEVIRTUAL:
                    op = "INVOKEVIRTUAL";
                    break;
                case Opcodes.INVOKESPECIAL:
                    op = "INVOKESPECIAL";
                    break;
                case Opcodes.INVOKESTATIC:
                    op = "INVOKESTATIC";
                    break;
                case Opcodes.INVOKEINTERFACE:
                    op = "INVOKEINTERFACE";
                    break;
            }
            return op + " " + owner + "/" + name + " " + desc;
        }
    }
}