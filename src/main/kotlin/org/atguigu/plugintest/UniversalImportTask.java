package org.atguigu.plugintest;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.apache.poi.ss.usermodel.*;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors; // 新增导入，用于简化列名引用

public class UniversalImportTask extends Task.Backgroundable {

    private final File excelFile;
    private final DbType dbType; // 新增：数据库类型
    private final String dbUrl;
    private final String user;
    private final String password;
    private final String tableName;
    private final List<String> columns;

    public UniversalImportTask(Project project, File excelFile, DbType dbType, String dbUrl, String user, String password, String tableName, List<String> columns) {
        super(project, "正在导入数据...", true);
        this.excelFile = excelFile;
        this.dbType = dbType;
        this.dbUrl = dbUrl;
        this.user = user;
        this.password = password;
        this.tableName = tableName;
        this.columns = columns;
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        // ----------------------------------------------------
        // 导入逻辑
        // ----------------------------------------------------
        FileInputStream fis = null;
        Workbook workbook = null;
        Connection conn = null;
        PreparedStatement ps = null;

        try {
            indicator.setText("正在加载 Excel 文件...");
            fis = new FileInputStream(excelFile);
            workbook = WorkbookFactory.create(fis);
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalStateException("Excel 文件中找不到工作表。");
            }

            // 获取最大行数，用于进度条
            int totalRows = sheet.getLastRowNum();
            // 第一行是表头，数据从第二行开始 (索引 1)
            int dataStartRow = 1;

            // ----------------------------------------------------
            // 1. 显式加载 JDBC 驱动
            // 这对于非标准驱动（如达梦）至关重要，确保驱动在 IntelliJ 环境中正确注册。
            Class.forName(dbType.getDriverClass());

            indicator.setText("正在连接数据库...");
            conn = DriverManager.getConnection(dbUrl, user, password);
            conn.setAutoCommit(false); // 启用事务

            // ----------------------------------------------------
            // 2. 构建 SQL 语句
            // ----------------------------------------------------
            // 修复 ORA-00942/表或视图不存在错误：
            // 对表名和列名使用双引号 (")，以保证在达梦/Oracle 等数据库中大小写敏感的标识符被正确识别。
            String quotedColumnList = columns.stream()
                    .map(col -> "\"" + col + "\"")
                    .collect(Collectors.joining(", "));

            String placeholderList = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));

            // 注意：对表名也使用双引号
            String insertSql = String.format("INSERT INTO \"%s\" (%s) VALUES (%s)", tableName, quotedColumnList, placeholderList);

            ps = conn.prepareStatement(insertSql);
            int count = 0;
            final int batchSize = 1000;

            // ----------------------------------------------------
            // 3. 逐行读取数据并执行批量插入
            // ----------------------------------------------------
            for (int i = dataStartRow; i <= totalRows; i++) {
                if (indicator.isCanceled()) {
                    throw new InterruptedException("导入任务被取消。");
                }

                Row row = sheet.getRow(i);
                if (row == null) continue; // 跳过空行

                indicator.setText2("正在处理第 " + (i + 1) + " 行...");
                indicator.setFraction((double) i / totalRows);

                // 为每个选中的列设置 PreparedStatement 参数
                int colIndex = 0;
                for (int j = 0; j < columns.size(); j++) {
                    // 假设 columns 列表中的顺序与 Excel 中的列顺序一致（即用户在对话框中选择的顺序）
                    Cell cell = row.getCell(j); // 假设选中列的顺序对应 Excel 中的列索引 j
                    Object cellValue = getCellValue(cell);
                    ps.setObject(j + 1, cellValue);
                }

                ps.addBatch();
                count++;

                // 批量提交
                if (count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                    indicator.setText("已提交 " + count + " 条数据批次...");
                }
            }

            // 提交剩余的批次
            ps.executeBatch();
            conn.commit();

            int finalCount = count;
            SwingUtilities.invokeLater(() ->
                    Messages.showInfoMessage("成功向 " + dbType + " 导入 " + finalCount + " 条数据！", "完成")
            );

        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignored) {}
            // e.printStackTrace(); // 调试用
            SwingUtilities.invokeLater(() ->
                    Messages.showErrorDialog("导入失败: " + e.getMessage(), "错误")
            );
        } finally {
            try { if (workbook != null) workbook.close(); } catch (Exception ignored) {}
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (ps != null) ps.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }

    // 辅助方法：获取单元格值
    private Object getCellValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // 转换为 SQL Timestamp
                    return new java.sql.Timestamp(cell.getDateCellValue().getTime());
                }
                // 默认返回 Double，数据库会自行转换
                return cell.getNumericCellValue();
            case BOOLEAN: return cell.getBooleanCellValue();
            case FORMULA:
                // 尝试解析公式结果
                try {
                    DataFormatter formatter = new DataFormatter();
                    // 这里可能需要更复杂的公式解析逻辑，但简单起见，先获取字符串格式
                    return formatter.formatCellValue(cell);
                } catch (Exception ignored) {
                    return null;
                }
            case BLANK:
            case ERROR:
            default: return null;
        }
    }
}
