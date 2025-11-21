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
        Connection conn = null;
        PreparedStatement ps = null;
        FileInputStream fis = null;
        Workbook workbook = null;

        try {
            // 1. 动态加载驱动
            indicator.setText("加载 " + dbType.toString() + " 驱动...");
            try {
                Class.forName(dbType.getDriverClass());
            } catch (ClassNotFoundException e) {
                throw new Exception("未找到驱动类: " + dbType.getDriverClass() + "。请检查插件依赖是否包含对应 jar 包。");
            }

            // 2. 建立连接
            indicator.setText("连接数据库...");
            conn = DriverManager.getConnection(dbUrl, user, password);
            conn.setAutoCommit(false); // 开启事务

            // 3. 准备 SQL
            StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            sqlBuilder.append(String.join(", ", columns));
            sqlBuilder.append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                sqlBuilder.append(i == 0 ? "?" : ", ?");
            }
            sqlBuilder.append(")");

            ps = conn.prepareStatement(sqlBuilder.toString());

            // 4. 读取 Excel
            indicator.setText("读取 Excel 文件...");
            fis = new FileInputStream(excelFile);
            workbook = WorkbookFactory.create(fis);
            Sheet sheet = workbook.getSheetAt(0);

            // 映射表头
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> colIndexMap = new HashMap<>();
            for (Cell cell : headerRow) {
                colIndexMap.put(cell.getStringCellValue(), cell.getColumnIndex());
            }

            int totalRows = sheet.getLastRowNum();
            int batchSize = 1000;
            int count = 0;

            // 5. 执行导入
            for (int i = 1; i <= totalRows; i++) {
                if (indicator.isCanceled()) break;

                Row row = sheet.getRow(i);
                if (row == null) continue;

                for (int k = 0; k < columns.size(); k++) {
                    String colName = columns.get(k);
                    Integer colIdx = colIndexMap.get(colName);
                    if (colIdx != null) {
                        ps.setObject(k + 1, getCellValue(row.getCell(colIdx)));
                    } else {
                        ps.setObject(k + 1, null);
                    }
                }

                ps.addBatch();
                count++;

                // 更新进度
                indicator.setFraction((double) i / totalRows);
                indicator.setText("导入中 (" + dbType + "): " + i + " / " + totalRows);

                if (count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }

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
                    return new java.sql.Timestamp(cell.getDateCellValue().getTime());
                }
                return cell.getNumericCellValue();
            case BOOLEAN: return cell.getBooleanCellValue();
            default: return null;
        }
    }
}
