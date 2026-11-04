import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.wltea.analyzer.cfg.Configuration;
import org.wltea.analyzer.dic.Dictionary;
import org.wltea.analyzer.lucene.IKTokenizer;

/**
 * 使用已安装IK的独立JVM测试小词表，只修改当前进程内的Dictionary单例。
 * 不运行Elasticsearch节点，不修改配置文件，不对现有节点热加载或重启。
 * 通过Base64行协议交换文本，避免中文、制表符和标点破坏输入输出边界。
 */
public class EvaluateIkDictionary {
    /** 显式使用本机既有词典；关闭远程词典监测，避免评测下载数据或后台刷新。 */
    private static final class LocalConfiguration extends Configuration {
        private final Path directory;

        LocalConfiguration(Path directory, boolean smart) {
            this.directory = directory;
            useSmart = smart;
            enableRemoteDict = false;
            enableLowercase = true;
        }

        public Path getConfDir() { return directory; }
        public Path getConfigInPluginDir() { return directory; }
        public Path getPath(String first, String... more) { return Path.of(first, more); }
    }

    /** 复用真实Lucene IKTokenizer而非自己实现切词，输出可与_analyze逐项对照的词项。 */
    private static String tokens(Path directory, String text, boolean smart) throws Exception {
        List<String> encoded = new ArrayList<>();
        try (IKTokenizer tokenizer = new IKTokenizer(new LocalConfiguration(directory, smart))) {
            tokenizer.setReader(new StringReader(text));
            CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                encoded.add(Base64.getEncoder().encodeToString(term.toString().getBytes(StandardCharsets.UTF_8)));
            }
            tokenizer.end();
        }
        return String.join(",", encoded);
    }

    /** 每个阶段只在独立JVM中增加词；输出词项覆盖诊断，不模拟BM25或短语位置评分。 */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        List<String> inputs = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        List<String> additions = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
        Path destination = Path.of(args[3]);
        Dictionary.initial(new LocalConfiguration(directory, false));
        try (var output = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            for (int phase = 0; phase <= additions.size(); phase++) {
                if (phase > 0) Dictionary.getSingleton().addWords(List.of(additions.get(phase - 1).split(",")));
                for (String line : inputs) {
                    String[] parts = line.split("\t", 2);
                    String text = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    for (boolean smart : new boolean[]{true, false}) {
                        output.write(phase + "\t" + parts[0] + "\t" + (smart ? "ik_smart" : "ik_max_word")
                                     + "\t" + tokens(directory, text, smart));
                        output.newLine();
                    }
                }
            }
        }
    }
}
