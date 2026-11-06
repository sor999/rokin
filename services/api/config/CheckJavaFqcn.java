import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class CheckJavaFqcn {
    private static final Pattern TYPE = Pattern.compile("(?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*");

    public static void main(String[] roots) throws Exception {
        var files = new ArrayList<Path>();
        for (var root : roots) {
            try (var paths = Files.walk(Path.of(root))) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var violations = new ArrayList<String>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(files));
            var units = task.parse();
            var positions = Trees.instance(task).getSourcePositions();
            for (var unit : units) {
                var imports = new HashMap<String, String>();
                var packageRoots = new HashSet<>(Set.of("java", "javax", "jakarta", "org", "com", "io", "lombok"));
                if (unit.getPackageName() != null) packageRoots.add(unit.getPackageName().toString().split("\\.")[0]);
                for (ImportTree declaration : unit.getImports()) {
                    var qualified = declaration.getQualifiedIdentifier().toString();
                    packageRoots.add(qualified.split("\\.")[0]);
                    if (!declaration.isStatic() && !qualified.endsWith(".*")) {
                        imports.put(qualified.substring(qualified.lastIndexOf('.') + 1), qualified);
                    }
                }
                var declaredTypes = new HashSet<String>();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree tree, Void unused) {
                        declaredTypes.add(tree.getSimpleName().toString());
                        return super.visitClass(tree, unused);
                    }
                }.scan(unit.getTypeDecls(), null);
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                        var qualified = tree.toString();
                        if (TYPE.matcher(qualified).matches() && packageRoots.contains(qualified.split("\\.")[0])) {
                            var simple = tree.getIdentifier().toString();
                            var conflictingImport = imports.get(simple);
                            if (!declaredTypes.contains(simple)
                                    && (conflictingImport == null || conflictingImport.equals(qualified))) {
                                long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
                                violations.add(unit.getSourceFile().getName() + ":" + line + ": "
                                        + qualified + " -> import 후 " + simple + " 사용 필요");
                            }
                        }
                        return super.visitMemberSelect(tree, unused);
                    }
                }.scan(unit.getTypeDecls(), null);
            }
        }
        violations.forEach(System.err::println);
        var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        errors.forEach(System.err::println);
        if (!violations.isEmpty() || !errors.isEmpty()) System.exit(1);
        System.out.println("FQCN 검사 통과: " + files.size() + " Java 파일");
    }
}
