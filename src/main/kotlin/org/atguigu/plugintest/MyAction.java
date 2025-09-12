package org.atguigu.plugintest;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class MyAction extends AnAction {
    private static final int MAX_LOG_LINES = 1000;
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    static class EnvConfig {
        private String name, host, user, password, logPath;
        public EnvConfig(String name, String host, String user, String password, String logPath) {
            this.name = name; this.host = host; this.user = user; this.password = password; this.logPath = logPath;
        }
        public String getName() { return name; }
        public String getHost() { return host; }
        public String getUser() { return user; }
        public String getPassword() { return password; }
        public String getLogPath() { return logPath; }
        public String toString() { return name; }
    }

    static class ConfigManager {
        private static final Path CONFIG_PATH = Paths.get(System.getProperty("user.home"), ".sshlogviewer", "envs.json");
        private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

        public static List<EnvConfig> loadConfigs() {
            try {
                if (!Files.exists(CONFIG_PATH)) return new ArrayList<>();
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                    return gson.fromJson(reader, new TypeToken<List<EnvConfig>>() {}.getType());
                }
            } catch (Exception e) {
                e.printStackTrace(); return new ArrayList<>();
            }
        }

        public static void saveConfigs(List<EnvConfig> configs) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                    gson.toJson(configs, writer);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class LogReaderContext {
        private volatile boolean isReading = false;
        private Session session;
        private Session.Command command;
        private final EnvConfig env;
        private final String keyword;
        private final JTextPane logPane;

        public LogReaderContext(EnvConfig env, String keyword, JTextPane logPane) {
            this.env = env;
            this.keyword = keyword;
            this.logPane = logPane;
        }

        public void start() {
            isReading = true;
            executor.execute(() -> {
                try (SSHClient ssh = new SSHClient()) {
                    ssh.addHostKeyVerifier(new HostKeyVerifier() {
                        public boolean verify(String hostname, int port, PublicKey key) { return true; }
                        public List<String> findExistingAlgorithms(String hostname, int port) { return List.of(); }
                    });

                    ssh.connect(env.getHost());
                    ssh.authPassword(env.getUser(), env.getPassword());

                    boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
                    String cmd = hasKeyword
                            ? "grep -i " + quoteShell(keyword) + " " + env.getLogPath()
                            : "tail -f " + env.getLogPath();

                    session = ssh.startSession();
                    command = session.exec(cmd);

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(command.getInputStream(), StandardCharsets.UTF_8))) {

                        StyledDocument doc = logPane.getStyledDocument();
                        Style defaultStyle = logPane.addStyle("default", null);
                        Style keywordStyle = logPane.addStyle("keyword", null);
                        StyleConstants.setForeground(keywordStyle, Color.RED);

                        String line;
                        while ((line = reader.readLine()) != null && isReading) {
                            final String logLine = line;
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    doc.insertString(doc.getLength(), logLine + "\n",
                                            hasKeyword ? keywordStyle : defaultStyle);
                                    trimLog(doc);
                                    logPane.setCaretPosition(doc.getLength());
                                } catch (BadLocationException e) {
                                    e.printStackTrace();
                                }
                            });
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(null, "连接失败：" + ex.getMessage()));
                } finally {
                    stop();
                }
            });
        }

        public void stop() {
            isReading = false;
            try {
                if (command != null) command.close();
                if (session != null) session.close();
            } catch (IOException ignored) {}
        }

        public boolean isRunning() {
            return isReading;
        }
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        JFrame frame = new JFrame("SSHJ 实时日志查看");
        frame.setSize(900, 600);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());

        List<EnvConfig> configs = ConfigManager.loadConfigs();
        JComboBox<EnvConfig> envSelector = new JComboBox<>(configs.toArray(new EnvConfig[0]));
        JTextField keywordField = new JTextField("ERROR", 10);
        JButton startButton = new JButton("开始");
        JButton stopButton = new JButton("停止");
        JButton addConfigButton = new JButton("新增配置");
        JButton editConfigButton = new JButton("编辑配置");
        JButton deleteConfigButton = new JButton("删除配置");
        JButton clearLogButton = new JButton("清空日志");
        JButton exportLogButton = new JButton("导出日志");

        JTextPane logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(logPane);

        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("环境:")); topPanel.add(envSelector);
        topPanel.add(new JLabel("关键词:")); topPanel.add(keywordField);
        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(addConfigButton);
        topPanel.add(editConfigButton);
        topPanel.add(deleteConfigButton);
        topPanel.add(clearLogButton);
        topPanel.add(exportLogButton);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.setVisible(true);

        final LogReaderContext[] contextHolder = new LogReaderContext[1];

        startButton.addActionListener(ev -> {
            if (contextHolder[0] != null && contextHolder[0].isRunning()) {
                JOptionPane.showMessageDialog(null, "日志读取已启动，请先停止后再开始");
                return;
            }
            EnvConfig selected = (EnvConfig) envSelector.getSelectedItem();
            String keyword = keywordField.getText().trim();

            StyledDocument doc = logPane.getStyledDocument();
            try {
                doc.insertString(doc.getLength(), "\n开始读取 [" + selected.getName() + "] 的日志...\n", logPane.getStyle("default"));
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }

            LogReaderContext context = new LogReaderContext(selected, keyword, logPane);
            contextHolder[0] = context;
            context.start();
        });

        stopButton.addActionListener(ev -> {
            if (contextHolder[0] != null && contextHolder[0].isRunning()) {
                contextHolder[0].stop();
                JOptionPane.showMessageDialog(null, "日志读取已停止");
            } else {
                JOptionPane.showMessageDialog(null, "当前没有正在读取的日志");
            }
        });

        addConfigButton.addActionListener(ev -> {
            JTextField nameField = new JTextField();
            JTextField hostField = new JTextField();
            JTextField userField = new JTextField();
            JPasswordField passField = new JPasswordField();
            JTextField logPathField = new JTextField();

            JPanel panel = new JPanel(new GridLayout(0, 2));
            panel.add(new JLabel("名称")); panel.add(nameField);
            panel.add(new JLabel("IP")); panel.add(hostField);
            panel.add(new JLabel("用户名")); panel.add(userField);
            panel.add(new JLabel("密码")); panel.add(passField);
            panel.add(new JLabel("日志路径")); panel.add(logPathField);

            int result = JOptionPane.showConfirmDialog(null, panel, "新增环境配置", JOptionPane.OK_CANCEL_OPTION);
            if (result == JOptionPane.OK_OPTION) {
                EnvConfig cfg = new EnvConfig(
                        nameField.getText().trim(),
                        hostField.getText().trim(),
                        userField.getText().trim(),
                        new String(passField.getPassword()),
                        logPathField.getText().trim()
                );
                configs.add(cfg);
                ConfigManager.saveConfigs(configs);
                envSelector.addItem(cfg);
            }
        });

        editConfigButton.addActionListener(ev -> {
            EnvConfig selected = (EnvConfig) envSelector.getSelectedItem();
            if (selected == null) return;

            JTextField nameField = new JTextField(selected.getName());
            JTextField hostField = new JTextField(selected.getHost());
            JTextField userField = new JTextField(selected.getUser());
            JPasswordField passField = new JPasswordField(selected.getPassword());
            JTextField logPathField = new JTextField(selected.getLogPath());

            JPanel panel = new JPanel(new GridLayout(0, 2));
            panel.add(new JLabel("名称")); panel.add(nameField);
            panel.add(new JLabel("IP")); panel.add(hostField);
            panel.add(new JLabel("用户名")); panel.add(userField);
            panel.add(new JLabel("密码")); panel.add(passField);
            panel.add(new JLabel("日志路径")); panel.add(logPathField);

            int result = JOptionPane.showConfirmDialog(null, panel, "编辑环境配置", JOptionPane.OK_CANCEL_OPTION);
            if (result == JOptionPane.OK_OPTION) {
                selected.name = nameField.getText().trim();
                selected.host = hostField.getText().trim();
                selected.user = userField.getText().trim();
                selected.password = new String(passField.getPassword());
                selected.logPath = logPathField.getText().trim();

                ConfigManager.saveConfigs(configs);
                envSelector.repaint();
            }
        });

        deleteConfigButton.addActionListener(ev -> {
            EnvConfig selected = (EnvConfig) envSelector.getSelectedItem();
            if (selected == null) return;

            int confirm = JOptionPane.showConfirmDialog(null,
                    "确定要删除配置 [" + selected.getName() + "] 吗？",
                    "确认删除", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                configs.remove(selected);
                ConfigManager.saveConfigs(configs);
                envSelector.removeItem(selected);
                JOptionPane.showMessageDialog(null, "配置已删除");
            }
        });

        clearLogButton.addActionListener(ev -> logPane.setText(""));

        exportLogButton.addActionListener(ev -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("保存日志为文件");
            int result = fileChooser.showSaveDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writer.write(logPane.getText());
                    JOptionPane.showMessageDialog(null, "日志已保存到：" + file.getAbsolutePath());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(null, "保存失败：" + ex.getMessage());
                }
            }
        });
    }

    private void trimLog(StyledDocument doc) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        int lineCount = root.getElementCount();
        if (lineCount > MAX_LOG_LINES) {
            Element firstLine = root.getElement(0);
            doc.remove(0, firstLine.getEndOffset());
        }
    }

    private String quoteShell(String str) {
        return "'" + str.replace("'", "'\"'\"'") + "'";
    }
}
