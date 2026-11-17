package com.foodmap;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** 防止后续功能重新把技术适配器塞进service，或跨模块直接访问持久化细节。 */
class ArchitectureBoundaryTest {
    @Test void keepsServicesAndModulePersistenceBoundariesClear() throws Exception {
        var root=Path.of("src/main/java/com/foodmap");
        try(var paths=Files.walk(root)) {
            for(var path:paths.filter(p->p.toString().endsWith(".java")).toList()) {
                var relative=root.relativize(path);var module=relative.getName(0).toString();
                var source=Files.readString(path);
                if(path.getParent().getFileName().toString().equals("service")) {
                    assertThat(path.getFileName().toString()).endsWith("Service.java");
                    assertThat(source).doesNotContain("@Scheduled", "import com.aliyun.", "import javax.imageio.","import org.springframework.mail.");
                }
                var imports=Pattern.compile("import com\\.foodmap\\.(\\w+)\\.(mapper|entity)\\.").matcher(source);
                while(imports.find())assertThat(imports.group(1)).as("模块%s的持久化引用：%s",module,path).isEqualTo(module);
            }
        }
    }
}
