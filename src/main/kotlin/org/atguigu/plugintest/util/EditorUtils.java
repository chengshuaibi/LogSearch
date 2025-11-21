package org.atguigu.plugintest.util;

// 移除这个导入（如果有）：
// import com.intellij.ide.highlighter.SqlFileType;

// 导入通用的文本文件类型
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;


public class EditorUtils {

    /**
     * 在 IDEA 编辑器中打开一个虚拟的、只读的 SQL 文件
     */
    public static void openSqlInEditor(Project project, String sqlContent, String fileName) {

        // 💥 关键修正：使用 PlainTextFileType 替代 SqlFileType
        LightVirtualFile virtualFile = new LightVirtualFile(fileName, PlainTextFileType.INSTANCE, sqlContent);

        // 必须在 EDT (Event Dispatch Thread) 中执行 UI 操作
        ApplicationManager.getApplication().invokeLater(() -> {
            FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
            fileEditorManager.openFile(virtualFile, true);
        });
    }
}
