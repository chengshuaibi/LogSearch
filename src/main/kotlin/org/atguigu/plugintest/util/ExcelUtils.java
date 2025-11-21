package org.atguigu.plugintest.util;

import org.apache.poi.ss.usermodel.*;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class ExcelUtils {

    // 读取表头 (保持不变)
    public static List<String> readHeaders(File file) throws Exception {
        List<String> headers = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    headers.add(cell.getStringCellValue());
                }
            }
        }
        return headers;
    }

    // 新增：生成 SQL 预览文本
    public static String generatePreviewSql(File file, String tableName, List<String> columns) throws Exception {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            // 映射列索引
            Map<String, Integer> colIndexMap = new HashMap<>();
            for (Cell cell : headerRow) {
                colIndexMap.put(cell.getStringCellValue(), cell.getColumnIndex());
            }

            sb.append("-- SQL Preview for table: ").append(tableName).append("\n");

            // 遍历数据
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                sb.append("INSERT INTO ").append(tableName).append(" (");
                sb.append(String.join(", ", columns));
                sb.append(") VALUES (");

                List<String> values = new ArrayList<>();
                for (String colName : columns) {
                    Integer idx = colIndexMap.get(colName);
                    if (idx != null) {
                        values.add(formatValueForSql(row.getCell(idx), dateFormat));
                    } else {
                        values.add("NULL");
                    }
                }
                sb.append(String.join(", ", values));
                sb.append(");\n");
            }
        }
        return sb.toString();
    }

    // 辅助：将单元格转为 SQL 字符串值 (带引号)
    private static String formatValueForSql(Cell cell, SimpleDateFormat fmt) {
        if (cell == null) return "NULL";
        switch (cell.getCellType()) {
            case STRING:
                // 转义单引号
                return "'" + cell.getStringCellValue().replace("'", "''") + "'";
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return "'" + fmt.format(cell.getDateCellValue()) + "'";
                }
                // 处理数字，避免 10.0 这种情况
                double val = cell.getNumericCellValue();
                if (val == (long) val) return String.valueOf((long) val);
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "NULL";
        }
    }
}
