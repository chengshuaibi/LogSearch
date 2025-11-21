package org.atguigu.plugintest;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportConfigDialog extends DialogWrapper {

    private final List<String> originalHeaders;

    // 声明所有 UI 字段 (确保 ComboBox 也在这里)
    private ComboBox<DbType> dbTypeComboBox;
    private JTextField urlField;
    private JTextField userField;
    private JPasswordField passwordField;
    private JTextField tableNameField;
    private final Map<String, JBCheckBox> checkBoxMap = new LinkedHashMap<>();

    // 实例化持久化配置
    private final AppSettingsState settings = AppSettingsState.getInstance();

    public ImportConfigDialog(List<String> headers) {
        super(true);
        this.originalHeaders = headers;
        setTitle("数据导入配置");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {

        // 1. 初始化组件并从 settings 中加载上次的值
        dbTypeComboBox = new ComboBox<>(DbType.values());

        // 从持久化状态中加载值
        dbTypeComboBox.setSelectedItem(settings.dbType);
        urlField = new JTextField(settings.dbUrl);
        userField = new JTextField(settings.dbUser);
        tableNameField = new JTextField(settings.tableName);
        passwordField = new JPasswordField(); // 密码不加载历史值

        // 2. 添加监听器：切换数据库类型时，自动改变默认 URL
        dbTypeComboBox.addActionListener(e -> {
            DbType selected = (DbType) dbTypeComboBox.getSelectedItem();
            if (selected != null) {
                // 更新 URL 和默认用户
                urlField.setText(selected.getDefaultUrl());
                userField.setText(selected == DbType.DAMENG ? "SYSDBA" : "root");
            }
        });

        // 3. 构建表单面板 (FormBuilder)
        JPanel formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("数据库类型:", dbTypeComboBox)
                .addLabeledComponent("JDBC URL:", urlField)
                .addLabeledComponent("用户名:", userField)
                .addLabeledComponent("密码:", passwordField)
                .addSeparator()
                .addLabeledComponent("目标表名:", tableNameField)
                .getPanel();

        // 4. 构建字段选择面板
        JPanel fieldsPanel = new JPanel();
        fieldsPanel.setLayout(new BoxLayout(fieldsPanel, BoxLayout.Y_AXIS));
        for (String header : originalHeaders) {
            JBCheckBox checkBox = new JBCheckBox(header, true);
            checkBoxMap.put(header, checkBox);
            fieldsPanel.add(checkBox);
        }

        JBScrollPane scrollPane = new JBScrollPane(fieldsPanel);
        scrollPane.setPreferredSize(new Dimension(450, 200));
        scrollPane.setBorder(BorderFactory.createTitledBorder("选择要导入的列"));

        // 5. 组装
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.add(formPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        return mainPanel;
    }

    @Override
    protected void doOKAction() {
        // 1. 获取当前用户输入
        DbType currentDbType = getSelectedDbType();
        String currentDbUrl = getDbUrl();
        String currentUser = getDbUser();
        String currentTableName = getTableName();

        // 2. 保存到全局状态
        settings.dbType = currentDbType;
        settings.dbUrl = currentDbUrl;
        settings.dbUser = currentUser;
        settings.tableName = currentTableName;

        // 3. 执行默认的 OK 动作
        super.doOKAction();
    }

    // Getters
    public DbType getSelectedDbType() {
        return (DbType) dbTypeComboBox.getSelectedItem();
    }
    public String getDbUrl() {
        return urlField.getText().trim();
    }
    public String getDbUser() {
        return userField.getText().trim();
    }
    public String getDbPassword() {
        return new String(passwordField.getPassword());
    }
    public String getTableName() {
        return tableNameField.getText().trim();
    }

    public List<String> getSelectedColumns() {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, JBCheckBox> entry : checkBoxMap.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        return selected;
    }
}
