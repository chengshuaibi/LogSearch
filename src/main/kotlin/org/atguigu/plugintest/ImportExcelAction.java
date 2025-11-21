package org.atguigu.plugintest;

// ... (其他导入)
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;

import org.atguigu.plugintest.util.EditorUtils;
import org.atguigu.plugintest.util.ExcelUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ImportExcelAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // ... (文件选择和配置对话框逻辑) ...

        VirtualFile virtualFile = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("xlsx"),
                project, null
        );
        if (virtualFile == null) return;
        File file = new File(virtualFile.getPath());

        try {
            List<String> headers = ExcelUtils.readHeaders(file);
            if (headers.isEmpty()) {
                Messages.showErrorDialog("Excel 表头为空", "错误");
                return;
            }

            ImportConfigDialog configDialog = new ImportConfigDialog(headers);
            if (!configDialog.showAndGet()) {
                return;
            }

            // 获取配置参数
            DbType dbType = configDialog.getSelectedDbType();
            String dbUrl = configDialog.getDbUrl();
            String user = configDialog.getDbUser();
            String password = configDialog.getDbPassword();
            String tableName = configDialog.getTableName();
            List<String> columns = configDialog.getSelectedColumns();

            if (tableName.isEmpty() || columns.isEmpty()) {
                Messages.showWarningDialog("表名或列不能为空", "提示");
                return;
            }

            // 1. 后台任务：生成预览 SQL
            AtomicReference<String> previewSqlRef = new AtomicReference<>();

            ProgressManager.getInstance().run(new Task.Modal(project, "正在生成 SQL 预览...", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        indicator.setIndeterminate(true);
                        String sql = ExcelUtils.generatePreviewSql(file, tableName, columns);
                        previewSqlRef.set(sql);
                    } catch (Exception ex) {
                        ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("生成预览失败: " + ex.getMessage(), "错误")
                        );
                    }
                }
            });

            String generatedSql = previewSqlRef.get();
            if (generatedSql == null) return;

            // 2. 在编辑器中打开 SQL 脚本 (文件会保留，符合您的要求)
            EditorUtils.openSqlInEditor(project, generatedSql, tableName + "_Import_Preview.sql");

            // 3. 弹出确认对话框，询问是否执行导入
            int result = Messages.showYesNoDialog(
                project,
                "SQL 脚本已在编辑器中打开，请确认。\n是否立即执行数据库导入操作？",
                "确认导入",
                "是，执行导入",
                "否，仅保留脚本",
                Messages.getQuestionIcon()
            );

            // 4. 确认执行
            if (result == Messages.YES) {
                // 运行真正的数据库导入任务
                ProgressManager.getInstance().run(
                    new UniversalImportTask(project, file, dbType, dbUrl, user, password, tableName, columns)
                );
            }
            // 5. 如果用户选否，则脚本保留在编辑器中。

        } catch (Exception ex) {
            Messages.showErrorDialog("操作异常: " + ex.getMessage(), "错误");
        }
    }
}
