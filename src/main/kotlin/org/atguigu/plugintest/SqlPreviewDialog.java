package org.atguigu.plugintest;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

public class SqlPreviewDialog extends DialogWrapper {

    private final String sqlContent;
    // 修正：移除 final 关键字
    private JTextArea textArea;

    public SqlPreviewDialog(String sqlContent) {
        super(true);
        this.sqlContent = sqlContent;
        setTitle("预览 SQL (确认无误后点击导入)");
        setOKButtonText("执行导入 (Import)");
        setCancelButtonText("取消");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel tipLabel = new JLabel("<html>以下是生成的 SQL 预览。<br>您可以复制备份，点击 <b>[执行导入]</b> 将数据写入数据库。</html>");
        tipLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        panel.add(tipLabel, BorderLayout.NORTH);

        // SQL 文本区域
        // 这里进行初始化
        textArea = new JTextArea(sqlContent);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JBScrollPane scrollPane = new JBScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 400));

        panel.add(scrollPane, BorderLayout.CENTER);

        JButton copyBtn = new JButton("复制 SQL 到剪贴板");
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sqlContent), null);
            JOptionPane.showMessageDialog(panel, "复制成功！");
        });

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomPanel.add(copyBtn);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }
}
