package org.atguigu.plugintest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MyAction extends AnAction {

    private static final int MAX_LOG_LINES = 1000;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int COMMAND_TIMEOUT = 8000;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    // ==================== 环境配置 ====================

    public static class EnvConfig {
        private String name;
        private String host;
        private String user;
        private String password;
        private String logPath;

        public EnvConfig() {
        }

        public EnvConfig(String name, String host, String user,
                         String password, String logPath) {
            this.name = name;
            this.host = host;
            this.user = user;
            this.password = password;
            this.logPath = logPath;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }

        public String getLogPath() {
            return logPath;
        }

        @Override
        public String toString() {
            return name == null ? "" : name;
        }
    }

    // ==================== 配置文件 ====================

    public static class ConfigManager {

        private static final Path CONFIG_PATH = Paths.get(
                System.getProperty("user.home"),
                ".sshlogviewer",
                "envs.json"
        );

        private static final Gson GSON =
                new GsonBuilder().setPrettyPrinting().create();

        public static synchronized List<EnvConfig> loadConfigs() {
            if (!Files.exists(CONFIG_PATH)) {
                return new ArrayList<>();
            }

            try (Reader reader = Files.newBufferedReader(
                    CONFIG_PATH, StandardCharsets.UTF_8)) {

                List<EnvConfig> configs = GSON.fromJson(
                        reader,
                        new TypeToken<List<EnvConfig>>() {
                        }.getType()
                );

                return configs == null
                        ? new ArrayList<>()
                        : configs;

            } catch (Exception e) {
                throw new RuntimeException(
                        "读取配置文件失败：" + e.getMessage(), e);
            }
        }

        public static synchronized void saveConfigs(
                List<EnvConfig> configs) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());

                try (Writer writer = Files.newBufferedWriter(
                        CONFIG_PATH, StandardCharsets.UTF_8)) {
                    GSON.toJson(configs, writer);
                }

            } catch (Exception e) {
                throw new RuntimeException(
                        "保存配置文件失败：" + e.getMessage(), e);
            }
        }
    }

    // ==================== SSH 工具 ====================

    private HostKeyVerifier createHostKeyVerifier() {
        return new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port,
                                  PublicKey key) {
                return true;
            }

            @Override
            public List<String> findExistingAlgorithms(
                    String hostname, int port) {
                return Collections.emptyList();
            }
        };
    }

    private SSHClient createSshClient() {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(createHostKeyVerifier());
        ssh.setConnectTimeout(CONNECT_TIMEOUT);
        ssh.setTimeout(COMMAND_TIMEOUT);
        return ssh;
    }

    private void closeSsh(SSHClient ssh) {
        if (ssh == null) {
            return;
        }

        try {
            ssh.disconnect();
        } catch (Exception ignored) {
        }

        try {
            ssh.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Shell 参数转义，避免路径或关键词中的特殊字符影响命令。
     */
    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // ==================== SSH 连接测试 ====================

    private String testConnection(EnvConfig config) {
        SSHClient ssh = null;

        try {
            ssh = createSshClient();
            ssh.connect(config.getHost());
            ssh.authPassword(config.getUser(), config.getPassword());

            try (Session session = ssh.startSession()) {
                String path = quoteShell(config.getLogPath());

                String command =
                        "if [ -f " + path + " ]; then "
                                + "echo LOG_EXISTS; "
                                + "elif [ -e " + path + " ]; then "
                                + "echo LOG_NOT_FILE; "
                                + "else echo LOG_NOT_EXISTS; fi";

                try (Session.Command cmd = session.exec(command)) {
                    cmd.join(5, TimeUnit.SECONDS);

                    StringBuilder output = new StringBuilder();

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    cmd.getInputStream(),
                                    StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    }

                    String result = output.toString();

                    if (result.contains("LOG_EXISTS")) {
                        return "测试成功：SSH 连接正常，日志文件存在。";
                    }

                    if (result.contains("LOG_NOT_FILE")) {
                        return "SSH 连接成功，但指定路径不是普通文件。";
                    }

                    if (result.contains("LOG_NOT_EXISTS")) {
                        return "SSH 连接成功，但日志文件不存在或当前用户无法访问。";
                    }

                    return "SSH 已连接，但未能确认日志文件状态。"
                            + "命令退出码：" + cmd.getExitStatus();
                }
            }

        } catch (Exception e) {
            return "测试失败：" + e.getClass().getSimpleName()
                    + " - " + e.getMessage();

        } finally {
            closeSsh(ssh);
        }
    }

    // ==================== 日志读取上下文 ====================

    private class LogReaderContext {

        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private final AtomicReference<SSHClient> sshRef =
                new AtomicReference<>();

        private final AtomicReference<Session> sessionRef =
                new AtomicReference<>();

        private final AtomicReference<Session.Command> commandRef =
                new AtomicReference<>();

        private final EnvConfig env;
        private final String keyword;
        private final JTextPane logPane;
        private final ExecutorService executor;

        private final BlockingQueue<String> logQueue =
                new LinkedBlockingQueue<>();

        private volatile Timer flushTimer;

        LogReaderContext(EnvConfig env, String keyword,
                         JTextPane logPane, ExecutorService executor) {
            this.env = env;
            this.keyword = keyword;
            this.logPane = logPane;
            this.executor = executor;
        }

        public void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }

            flushTimer = new Timer(100, e -> flushLogsToUI());
            flushTimer.start();

            try {
                executor.execute(this::readLogs);
            } catch (RejectedExecutionException e) {
                stop();
                throw e;
            }
        }

        private void readLogs() {
            SSHClient ssh = null;
            Session session = null;
            Session.Command command = null;

            try {
                if (!running.get()) {
                    return;
                }

                ssh = createSshClient();
                sshRef.set(ssh);

                ssh.connect(env.getHost());
                ssh.authPassword(env.getUser(), env.getPassword());

                if (!running.get()) {
                    return;
                }

                session = ssh.startSession();
                sessionRef.set(session);

                String path = quoteShell(env.getLogPath());
                boolean hasKeyword =
                        keyword != null && !keyword.trim().isEmpty();

                String shellCommand;

                if (hasKeyword) {
                    // 最近 1000 行历史日志 + 后续新增匹配日志
                    shellCommand = "tail -n 1000 -F " + path
                            + " | grep --line-buffered -a -i -F -- "
                            + quoteShell(keyword);
                } else {
                    // 保留原来的默认行为：读取末尾日志并持续跟踪
                    shellCommand = "tail -F " + path;
                }

                if (!running.get()) {
                    return;
                }

                command = session.exec(shellCommand);
                commandRef.set(command);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                command.getInputStream(),
                                StandardCharsets.UTF_8))) {

                    String line;
                    while (running.get()
                            && !Thread.currentThread().isInterrupted()
                            && (line = reader.readLine()) != null) {
                        logQueue.offer(line);
                    }
                }

                if (running.get()) {
                    showReadError("远程日志读取已结束。");
                }

            } catch (Exception e) {
                if (running.get()
                        && !Thread.currentThread().isInterrupted()) {
                    showReadError("连接异常：" + e.getMessage());
                }

            } finally {
                closeCommand(command);
                closeSession(session);
                closeSsh(ssh);
                stop();
            }
        }

        private void flushLogsToUI() {
            if (!logPane.isDisplayable() || logQueue.isEmpty()) {
                return;
            }

            List<String> lines = new ArrayList<>();
            logQueue.drainTo(lines);

            if (lines.isEmpty()) {
                return;
            }

            try {
                StyledDocument doc = logPane.getStyledDocument();

                Style style = logPane.getStyle(
                        keyword != null && !keyword.trim().isEmpty()
                                ? "keyword" : "default");

                if (style == null) {
                    style = logPane.addStyle(
                            keyword != null && !keyword.trim().isEmpty()
                                    ? "keyword" : "default", null);

                    if (keyword != null && !keyword.trim().isEmpty()) {
                        StyleConstants.setForeground(style, Color.RED);
                    }
                }

                StringBuilder text = new StringBuilder();
                for (String line : lines) {
                    text.append(line).append('\n');
                }

                doc.insertString(doc.getLength(), text.toString(), style);
                trimLog(doc);
                logPane.setCaretPosition(doc.getLength());

            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }

        private void showReadError(String message) {
            SwingUtilities.invokeLater(() -> {
                if (logPane.isDisplayable()) {
                    JOptionPane.showMessageDialog(
                            logPane, message,
                            "SSH 日志读取",
                            JOptionPane.ERROR_MESSAGE);
                }
            });
        }

        private void closeCommand(Session.Command command) {
            if (command != null) {
                commandRef.compareAndSet(command, null);
                try {
                    command.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void closeSession(Session session) {
            if (session != null) {
                sessionRef.compareAndSet(session, null);
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void closeSsh(SSHClient ssh) {
            if (ssh != null) {
                sshRef.compareAndSet(ssh, null);
                MyAction.this.closeSsh(ssh);
            }
        }

        public void stop() {
            running.set(false);

            if (!stopped.compareAndSet(false, true)) {
                return;
            }

            Timer timer = flushTimer;
            if (timer != null) {
                timer.stop();
            }

            Session.Command command = commandRef.getAndSet(null);
            if (command != null) {
                try {
                    command.close();
                } catch (Exception ignored) {
                }
            }

            Session session = sessionRef.getAndSet(null);
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }

            SSHClient ssh = sshRef.getAndSet(null);
            MyAction.this.closeSsh(ssh);

            // 停止时保留已收到的日志，最后刷新到界面
            SwingUtilities.invokeLater(() -> {
                if (logPane.isDisplayable()) {
                    flushLogsToUI();
                }
            });
        }

        public boolean isRunning() {
            return running.get();
        }
    }

    // ==================== 配置编辑弹窗（新增、编辑共用） ====================

    private EnvConfig showConfigDialog(
            JFrame frame,
            ExecutorService executor,
            EnvConfig original) {

        boolean editing = original != null;

        JTextField nameField = new JTextField(
                editing ? original.getName() : "");

        JTextField hostField = new JTextField(
                editing ? original.getHost() : "");

        JTextField userField = new JTextField(
                editing ? original.getUser() : "");

        JPasswordField passField = new JPasswordField(
                editing ? original.getPassword() : "");

        JTextField pathField = new JTextField(
                editing ? original.getLogPath() : "");

        JButton testButton = new JButton("测试连接");
        JLabel statusLabel = new JLabel(
                editing ? "原配置未修改" : "尚未测试");

        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));

        panel.add(new JLabel("名称"));
        panel.add(nameField);
        panel.add(new JLabel("IP / 主机名"));
        panel.add(hostField);
        panel.add(new JLabel("用户名"));
        panel.add(userField);
        panel.add(new JLabel("密码"));
        panel.add(passField);
        panel.add(new JLabel("日志路径"));
        panel.add(pathField);
        panel.add(testButton);
        panel.add(statusLabel);

        AtomicBoolean testPassed = new AtomicBoolean(editing);

        Runnable invalidateTest = () -> {
            testPassed.set(false);
            statusLabel.setText("配置已修改，请重新测试");
        };

        DocumentListener listener =
                new SimpleDocumentListener(invalidateTest);

        nameField.getDocument().addDocumentListener(listener);
        hostField.getDocument().addDocumentListener(listener);
        userField.getDocument().addDocumentListener(listener);
        passField.getDocument().addDocumentListener(listener);
        pathField.getDocument().addDocumentListener(listener);

        testButton.addActionListener(e -> {
            EnvConfig config = readConfig(
                    nameField, hostField, userField,
                    passField, pathField);

            if (!isConfigValid(config, frame)) {
                return;
            }

            testButton.setEnabled(false);
            testPassed.set(false);
            statusLabel.setText("正在连接服务器...");

            try {
                executor.execute(() -> {
                    String result = testConnection(config);
                    boolean success = result.startsWith("测试成功：");

                    SwingUtilities.invokeLater(() -> {
                        if (!frame.isDisplayable()) {
                            return;
                        }

                        testButton.setEnabled(true);
                        testPassed.set(success);
                        statusLabel.setText(
                                success ? "测试通过" : "测试未通过");

                        JOptionPane.showMessageDialog(
                                frame, result,
                                "SSH 连接测试结果",
                                success
                                        ? JOptionPane.INFORMATION_MESSAGE
                                        : JOptionPane.WARNING_MESSAGE);
                    });
                });
            } catch (RejectedExecutionException ex) {
                testButton.setEnabled(true);
                statusLabel.setText("线程池已关闭");
            }
        });

        int result = JOptionPane.showConfirmDialog(
                frame, panel,
                editing ? "编辑环境配置" : "新增环境配置",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        EnvConfig config = readConfig(
                nameField, hostField, userField,
                passField, pathField);

        if (!isConfigValid(config, frame)) {
            return null;
        }

        if (!testPassed.get()) {
            JOptionPane.showMessageDialog(
                    frame,
                    "请先点击“测试连接”，测试通过后再保存配置。",
                    "尚未通过连接测试",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        return config;
    }

    private EnvConfig readConfig(
            JTextField name, JTextField host, JTextField user,
            JPasswordField password, JTextField path) {

        return new EnvConfig(
                name.getText().trim(),
                host.getText().trim(),
                user.getText().trim(),
                new String(password.getPassword()),
                path.getText().trim());
    }

    private boolean isConfigValid(EnvConfig config, JFrame frame) {
        if (config.getName().isEmpty()
                || config.getHost().isEmpty()
                || config.getUser().isEmpty()
                || config.getPassword().isEmpty()
                || config.getLogPath().isEmpty()) {

            JOptionPane.showMessageDialog(
                    frame,
                    "所有配置项均不能为空。",
                    "参数不完整",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }

        return true;
    }

    // ==================== 主界面 ====================

    @Override
    public void actionPerformed(AnActionEvent e) {
        JFrame frame = new JFrame("SSHJ 实时日志查看");
        frame.setSize(1100, 650);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        ExecutorService executor = Executors.newFixedThreadPool(3);

        JComboBox<EnvConfig> envSelector = new JComboBox<>();
        JTextField keywordField = new JTextField("ERROR", 10);

        JButton startButton = new JButton("开始");
        JButton stopButton = new JButton("停止");
        JButton addButton = new JButton("新增配置");
        JButton editButton = new JButton("编辑配置");
        JButton deleteButton = new JButton("删除配置");
        JButton clearButton = new JButton("清空日志");
        JButton exportButton = new JButton("导出日志");

        JTextPane logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("环境："));
        topPanel.add(envSelector);
        topPanel.add(new JLabel("关键词："));
        topPanel.add(keywordField);
        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(addButton);
        topPanel.add(editButton);
        topPanel.add(deleteButton);
        topPanel.add(clearButton);
        topPanel.add(exportButton);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(new JScrollPane(logPane), BorderLayout.CENTER);

        AtomicReference<LogReaderContext> activeContext =
                new AtomicReference<>();

        Runnable reloadConfigs = () -> {
            List<EnvConfig> configs = ConfigManager.loadConfigs();
            DefaultComboBoxModel<EnvConfig> model =
                    new DefaultComboBoxModel<>();

            for (EnvConfig config : configs) {
                model.addElement(config);
            }

            envSelector.setModel(model);
        };

        try {
            reloadConfigs.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    frame, ex.getMessage(),
                    "读取配置失败", JOptionPane.ERROR_MESSAGE);
        }

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LogReaderContext context =
                        activeContext.getAndSet(null);

                if (context != null) {
                    context.stop();
                }

                executor.shutdownNow();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                executor.shutdownNow();
            }
        });

        frame.setVisible(true);

        // ==================== 开始读取 ====================

        startButton.addActionListener(ev -> {
            EnvConfig selected =
                    (EnvConfig) envSelector.getSelectedItem();

            if (selected == null) {
                JOptionPane.showMessageDialog(
                        frame, "请先选择或创建一个环境配置！");
                return;
            }

            String keyword = keywordField.getText().trim();

            try {
                executor.execute(() -> {
                    LogReaderContext previous =
                            activeContext.getAndSet(null);

                    if (previous != null) {
                        previous.stop();
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    SwingUtilities.invokeLater(() -> {
                        if (!frame.isDisplayable()) {
                            return;
                        }

                        try {
                            logPane.getStyledDocument().insertString(
                                    logPane.getDocument().getLength(),
                                    "\n开始读取 [" + selected.getName()
                                            + "] 的日志...\n",
                                    null);
                        } catch (BadLocationException ex) {
                            ex.printStackTrace();
                        }
                    });

                    LogReaderContext context = new LogReaderContext(
                            selected, keyword, logPane, executor);

                    activeContext.set(context);

                    try {
                        context.start();
                    } catch (RejectedExecutionException ex) {
                        activeContext.compareAndSet(context, null);
                    }
                });
            } catch (RejectedExecutionException ex) {
                JOptionPane.showMessageDialog(
                        frame, "线程池已关闭，无法启动读取任务。");
            }
        });

        // ==================== 停止读取 ====================

        stopButton.addActionListener(ev -> {
            LogReaderContext context = activeContext.getAndSet(null);

            if (context != null && context.isRunning()) {
                context.stop();
                JOptionPane.showMessageDialog(frame, "日志读取已停止。");
            } else {
                JOptionPane.showMessageDialog(
                        frame, "当前没有正在读取的日志。");
            }
        });

        // ==================== 新增配置 ====================

        addButton.addActionListener(ev -> {
            EnvConfig config = showConfigDialog(frame, executor, null);

            if (config == null) {
                return;
            }

            try {
                List<EnvConfig> configs = ConfigManager.loadConfigs();
                configs.add(config);
                ConfigManager.saveConfigs(configs);

                reloadConfigs.run();
                envSelector.setSelectedIndex(
                        envSelector.getItemCount() - 1);

                JOptionPane.showMessageDialog(frame, "新增配置成功！");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        frame, ex.getMessage(),
                        "保存失败", JOptionPane.ERROR_MESSAGE);
            }
        });

        // ==================== 编辑配置 ====================

        editButton.addActionListener(ev -> {
            int selectedIndex = envSelector.getSelectedIndex();

            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(
                        frame, "请先选择要编辑的配置！");
                return;
            }

            List<EnvConfig> configs;

            try {
                configs = ConfigManager.loadConfigs();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        frame, ex.getMessage(),
                        "读取配置失败", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (selectedIndex >= configs.size()) {
                return;
            }

            EnvConfig original = configs.get(selectedIndex);
            EnvConfig updated =
                    showConfigDialog(frame, executor, original);

            if (updated == null) {
                return;
            }

            configs.set(selectedIndex, updated);

            try {
                ConfigManager.saveConfigs(configs);
                reloadConfigs.run();
                envSelector.setSelectedIndex(selectedIndex);

                JOptionPane.showMessageDialog(frame, "修改配置成功！");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        frame, ex.getMessage(),
                        "保存失败", JOptionPane.ERROR_MESSAGE);
            }
        });

        // ==================== 删除配置 ====================

        deleteButton.addActionListener(ev -> {
            int selectedIndex = envSelector.getSelectedIndex();

            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(
                        frame, "请先选择要删除的配置！");
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(
                    frame,
                    "确定要删除当前选中的配置吗？",
                    "确认删除",
                    JOptionPane.YES_NO_OPTION);

            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }

            try {
                List<EnvConfig> configs = ConfigManager.loadConfigs();

                if (selectedIndex < configs.size()) {
                    configs.remove(selectedIndex);
                    ConfigManager.saveConfigs(configs);
                    reloadConfigs.run();

                    JOptionPane.showMessageDialog(frame, "配置已删除。");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        frame, ex.getMessage(),
                        "删除失败", JOptionPane.ERROR_MESSAGE);
            }
        });

        // ==================== 清空日志 ====================

        clearButton.addActionListener(ev -> logPane.setText(""));

        // ==================== 导出日志 ====================

        exportButton.addActionListener(ev -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("保存日志为文件");

            if (chooser.showSaveDialog(frame)
                    != JFileChooser.APPROVE_OPTION) {
                return;
            }

            File file = chooser.getSelectedFile();

            try (BufferedWriter writer = Files.newBufferedWriter(
                    file.toPath(), StandardCharsets.UTF_8)) {

                writer.write(logPane.getText());

                JOptionPane.showMessageDialog(
                        frame, "日志已保存到：" + file.getAbsolutePath());

            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        frame, "保存失败：" + ex.getMessage(),
                        "导出日志", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ==================== 日志裁剪 ====================

    private void trimLog(StyledDocument doc)
            throws BadLocationException {

        Element root = doc.getDefaultRootElement();
        int lineCount = root.getElementCount();

        if (lineCount > MAX_LOG_LINES) {
            Element firstLine = root.getElement(0);
            int removeLength = firstLine.getEndOffset();

            doc.remove(0, Math.min(removeLength, doc.getLength()));
        }
    }

    // ==================== 文本变化监听 ====================

    private static class SimpleDocumentListener
            implements DocumentListener {

        private final Runnable callback;

        SimpleDocumentListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            callback.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            callback.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            callback.run();
        }
    }
}