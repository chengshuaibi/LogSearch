package org.atguigu.plugintest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MyAction extends AnAction {

    private static final int MAX_LOG_LINES = 2000;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int COMMAND_TIMEOUT = 8000;
    /** 每次刷新到 UI 的最大行数，避免一次性插入过多导致 EDT 卡顿 */
    private static final int MAX_FLUSH_LINES = 500;

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

        public String getName() { return name; }
        public String getHost() { return host; }
        public String getUser() { return user; }
        public String getPassword() { return password; }
        public String getLogPath() { return logPath; }

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
                        new TypeToken<List<EnvConfig>>() {}.getType()
                );
                return configs == null ? new ArrayList<>() : configs;
            } catch (Exception e) {
                throw new RuntimeException("读取配置文件失败：" + e.getMessage(), e);
            }
        }

        public static synchronized void saveConfigs(List<EnvConfig> configs) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                try (Writer writer = Files.newBufferedWriter(
                        CONFIG_PATH, StandardCharsets.UTF_8)) {
                    GSON.toJson(configs, writer);
                }
            } catch (Exception e) {
                throw new RuntimeException("保存配置文件失败：" + e.getMessage(), e);
            }
        }
    }

    // ==================== SSH 工具 ====================
    private HostKeyVerifier createHostKeyVerifier() {
        return new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                return true;
            }
            @Override
            public List<String> findExistingAlgorithms(String hostname, int port) {
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
        if (ssh == null) return;
        try { ssh.disconnect(); } catch (Exception ignored) {}
        try { ssh.close(); } catch (Exception ignored) {}
    }

    /** Shell 参数转义，避免路径或关键词中的特殊字符影响命令 */
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
                            new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
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
                    return "SSH 已连接，但未能确认日志文件状态。命令退出码：" + cmd.getExitStatus();
                }
            }
        } catch (Exception e) {
            return "测试失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        } finally {
            closeSsh(ssh);
        }
    }

    // ==================== 日志读取上下文 ====================
    private class LogReaderContext {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private final AtomicReference<SSHClient> sshRef = new AtomicReference<>();
        private final AtomicReference<Session> sessionRef = new AtomicReference<>();
        private final AtomicReference<Session.Command> commandRef = new AtomicReference<>();
        /** 保存输入流，stop 时第一时间关闭它，打断阻塞的 readLine() */
        private final AtomicReference<InputStream> streamRef = new AtomicReference<>();

        private final EnvConfig env;
        private final String keyword;
        private final JTextArea logPane;
        private final ExecutorService executor;
        private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

        private volatile Timer flushTimer;

        LogReaderContext(EnvConfig env, String keyword,
                         JTextArea logPane, ExecutorService executor) {
            this.env = env;
            this.keyword = keyword;
            this.logPane = logPane;
            this.executor = executor;
        }

        public void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            // 使用 Swing 定时器，确保回调在 EDT 线程执行，避免渲染异常
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
                if (!running.get()) return;

                ssh = createSshClient();
                sshRef.set(ssh);
                ssh.connect(env.getHost());
                ssh.authPassword(env.getUser(), env.getPassword());

                if (!running.get()) return;

                session = ssh.startSession();
                sessionRef.set(session);

                String path = quoteShell(env.getLogPath());
                boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
                String shellCommand;

                if (hasKeyword) {
                    // 最近 1000 行历史日志 + 后续新增匹配日志
                    shellCommand = "tail -n 1000 -F " + path
                            + " | grep --line-buffered -a -i -F -- "
                            + quoteShell(keyword);
                } else {
                    // 读取末尾日志并持续跟踪
                    shellCommand = "tail -F " + path;
                }

                if (!running.get()) return;

                command = session.exec(shellCommand);
                commandRef.set(command);

                InputStream in = command.getInputStream();
                streamRef.set(in);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
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
                // 主动 stop 时输入流被关闭会抛异常，此时 running 已为 false，不再弹窗
                if (running.get() && !Thread.currentThread().isInterrupted()) {
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
            // 限制单次处理行数，避免一次性插入过多导致 EDT 卡顿
            logQueue.drainTo(lines, MAX_FLUSH_LINES);
            if (lines.isEmpty()) return;

            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                // 自动格式化行内 XML / JSON
                String formatted = autoFormatLog(line);
                text.append(formatted).append('\n');
            }

            // 追加日志，Swing 的 append 方法安全高效，不会破坏换行渲染
            logPane.append(text.toString());
            trimLog(logPane);
            // 自动滚动到底部
            logPane.setCaretPosition(logPane.getDocument().getLength());
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
                try { command.close(); } catch (Exception ignored) {}
            }
        }

        private void closeSession(Session session) {
            if (session != null) {
                sessionRef.compareAndSet(session, null);
                try { session.close(); } catch (Exception ignored) {}
            }
        }

        private void closeSsh(SSHClient ssh) {
            if (ssh != null) {
                sshRef.compareAndSet(ssh, null);
                MyAction.this.closeSsh(ssh);
            }
        }

        /**
         * 停止读取。会立刻关闭输入流打断 readLine()，
         * 把耗时的 SSH 关闭动作放到后台线程执行，避免阻塞 EDT。
         */
        public void stop() {
            running.set(false);
            if (!stopped.compareAndSet(false, true)) {
                return;
            }

            Timer timer = flushTimer;
            if (timer != null) timer.stop();

            // 1) 先关输入流，让 readLine() 立刻抛异常返回
            InputStream in = streamRef.getAndSet(null);
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }

            // 2) 后台线程执行耗时的关闭动作
            Thread shutdown = new Thread(() -> {
                Session.Command command = commandRef.getAndSet(null);
                if (command != null) {
                    try { command.close(); } catch (Exception ignored) {}
                }
                Session session = sessionRef.getAndSet(null);
                if (session != null) {
                    try { session.close(); } catch (Exception ignored) {}
                }
                SSHClient ssh = sshRef.getAndSet(null);
                MyAction.this.closeSsh(ssh);
            }, "ssh-log-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();

            // 3) 停止时保留已收到的日志，最后刷新到界面
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
        JTextField nameField = new JTextField(editing ? original.getName() : "");
        JTextField hostField = new JTextField(editing ? original.getHost() : "");
        JTextField userField = new JTextField(editing ? original.getUser() : "");
        JPasswordField passField = new JPasswordField(editing ? original.getPassword() : "");
        JTextField pathField = new JTextField(editing ? original.getLogPath() : "");
        JButton testButton = new JButton("测试连接");
        JLabel statusLabel = new JLabel(editing ? "原配置未修改" : "尚未测试");

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

        DocumentListener listener = new SimpleDocumentListener(invalidateTest);
        nameField.getDocument().addDocumentListener(listener);
        hostField.getDocument().addDocumentListener(listener);
        userField.getDocument().addDocumentListener(listener);
        passField.getDocument().addDocumentListener(listener);
        pathField.getDocument().addDocumentListener(listener);

        testButton.addActionListener(e -> {
            EnvConfig config = readConfig(nameField, hostField, userField, passField, pathField);
            if (!isConfigValid(config, frame)) return;

            testButton.setEnabled(false);
            testPassed.set(false);
            statusLabel.setText("正在连接服务器...");

            try {
                executor.execute(() -> {
                    String result = testConnection(config);
                    boolean success = result.startsWith("测试成功：");
                    SwingUtilities.invokeLater(() -> {
                        if (!frame.isDisplayable()) return;
                        testButton.setEnabled(true);
                        testPassed.set(success);
                        statusLabel.setText(success ? "测试通过" : "测试未通过");
                        JOptionPane.showMessageDialog(
                                frame, result,
                                "SSH 连接测试结果",
                                success ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
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

        if (result != JOptionPane.OK_OPTION) return null;

        EnvConfig config = readConfig(nameField, hostField, userField, passField, pathField);
        if (!isConfigValid(config, frame)) return null;

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

        // ========== 日志面板：自动换行核心配置 ==========
        JTextArea logPane = new JTextArea();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        // 强制开启自动换行
        logPane.setLineWrap(true);
        // false = 按字符边界强制折行，超长无空格文本也一定会换行
        logPane.setWrapStyleWord(false);
        // 设置边距，优化观感
        logPane.setMargin(new Insets(4, 6, 4, 6));

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

        // 滚动面板：关闭水平滚动条，强制视口宽度等于文本区宽度，触发换行
        JScrollPane scrollPane = new JScrollPane(logPane);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setWheelScrollingEnabled(true);
        frame.add(scrollPane, BorderLayout.CENTER);

        AtomicReference<LogReaderContext> activeContext = new AtomicReference<>();

        Runnable reloadConfigs = () -> {
            List<EnvConfig> configs = ConfigManager.loadConfigs();
            DefaultComboBoxModel<EnvConfig> model = new DefaultComboBoxModel<>();
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
                LogReaderContext context = activeContext.getAndSet(null);
                if (context != null) context.stop();
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
            EnvConfig selected = (EnvConfig) envSelector.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(frame, "请先选择或创建一个环境配置！");
                return;
            }
            String keyword = keywordField.getText().trim();

            try {
                executor.execute(() -> {
                    LogReaderContext previous = activeContext.getAndSet(null);
                    if (previous != null) previous.stop();
                    if (Thread.currentThread().isInterrupted()) return;

                    SwingUtilities.invokeLater(() -> {
                        if (!frame.isDisplayable()) return;
                        logPane.append("\n开始读取 [" + selected.getName() + "] 的日志...\n");
                        logPane.setCaretPosition(logPane.getDocument().getLength());
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
                JOptionPane.showMessageDialog(frame, "线程池已关闭，无法启动读取任务。");
            }
        });

        // ==================== 停止读取 ====================
        stopButton.addActionListener(ev -> {
            LogReaderContext context = activeContext.getAndSet(null);
            if (context != null && context.isRunning()) {
                context.stop();
                JOptionPane.showMessageDialog(frame, "日志读取已停止。");
            } else {
                JOptionPane.showMessageDialog(frame, "当前没有正在读取的日志。");
            }
        });

        // ==================== 新增配置 ====================
        addButton.addActionListener(ev -> {
            EnvConfig config = showConfigDialog(frame, executor, null);
            if (config == null) return;
            try {
                List<EnvConfig> configs = ConfigManager.loadConfigs();
                configs.add(config);
                ConfigManager.saveConfigs(configs);
                reloadConfigs.run();
                envSelector.setSelectedIndex(envSelector.getItemCount() - 1);
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
                JOptionPane.showMessageDialog(frame, "请先选择要编辑的配置！");
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
            if (selectedIndex >= configs.size()) return;

            EnvConfig original = configs.get(selectedIndex);
            EnvConfig updated = showConfigDialog(frame, executor, original);
            if (updated == null) return;

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
                JOptionPane.showMessageDialog(frame, "请先选择要删除的配置！");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(
                    frame,
                    "确定要删除当前选中的配置吗？",
                    "确认删除",
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;

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
            if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            File file = chooser.getSelectedFile();
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file.toPath(), StandardCharsets.UTF_8)) {
                writer.write(logPane.getText());
                JOptionPane.showMessageDialog(frame, "日志已保存到：" + file.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        frame, "保存失败：" + ex.getMessage(),
                        "导出日志", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ==================== 日志格式化核心（行内提取+格式化+双重Unicode解码） ====================
    /**
     * 自动识别行内的 JSON / XML 片段并格式化
     * 前缀时间、类名、描述文字原样保留
     */
    private String autoFormatLog(String line) {
        if (line == null || line.trim().isEmpty()) {
            return line;
        }
        // 先格式化行内所有 JSON 片段
        String afterJson = formatInlineJson(line);
        // 再格式化行内 XML 片段
        return formatInlineXml(afterJson);
    }

    /**
     * 提取并格式化行内所有完整 JSON 片段（支持嵌套、转义）
     * 空对象/空数组不格式化，避免无意义换行
     */
    private String formatInlineJson(String line) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            // 识别 JSON 起始：对象 { 或数组 [
            if (c == '{' || c == '[') {
                int end = findMatchingBracket(line, i);
                if (end > i) {
                    String jsonStr = line.substring(i, end + 1);
                    // 空对象/空数组 不格式化，直接跳过
                    if ("{}".equals(jsonStr) || "[]".equals(jsonStr)) {
                        sb.append(jsonStr);
                        i = end + 1;
                        continue;
                    }
                    String pretty = formatJson(jsonStr);
                    // 格式化成功则替换
                    if (!pretty.equals(jsonStr)) {
                        sb.append('\n').append(pretty).append('\n');
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 括号平衡算法：找到与起始括号匹配的闭合括号位置
     * 处理字符串内的括号、转义字符，避免误匹配
     */
    private int findMatchingBracket(String str, int start) {
        char open = str.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = start; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (ch == open) depth++;
            else if (ch == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 提取并格式化行内 XML 片段
     */
    private String formatInlineXml(String line) {
        // 查找 XML 起始位置：< 后接 ? 或字母
        int start = -1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '<' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == '?' || Character.isLetter(next)) {
                    start = i;
                    break;
                }
            }
        }
        if (start == -1) return line;

        // 查找最后一个 > 作为结束
        int end = line.lastIndexOf('>');
        if (end <= start) return line;

        String xmlStr = line.substring(start, end + 1);
        String pretty = formatXml(xmlStr);
        if (!pretty.equals(xmlStr)) {
            // 前缀 + 格式化XML + 后缀
            return line.substring(0, start) + "\n" + pretty + "\n" + line.substring(end + 1);
        }
        return line;
    }

    /**
     * JSON 美化格式化
     * 1. 先对原始字符串做一次 Unicode 解码，处理双重转义的字面量
     * 2. 使用 JsonElement 解析，保留原始类型，避免数字字符串被转成科学计数法
     * 3. 递归解码节点内残留的 Unicode 转义
     * 4. 输出后再做一次兜底解码
     */
    private String formatJson(String json) {
        try {
            // 第一步：原始字符串先解码一次，处理双重转义
            String decodedRaw = decodeUnicodeEscapes(json);

            Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
            JsonElement element = JsonParser.parseString(decodedRaw);
            // 第二步：递归解码所有节点内的字符串
            element = decodeUnicodeInJson(element);

            String result = prettyGson.toJson(element);
            // 第三步：输出后兜底解码，处理 Gson 序列化时再次转义的字符
            return decodeUnicodeEscapes(result);
        } catch (Exception e) {
            // 解析失败原样返回
            return json;
        }
    }

    /**
     * 递归遍历JSON节点，解码所有字符串值中的Unicode转义
     */
    private JsonElement decodeUnicodeInJson(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String decoded = decodeUnicodeEscapes(primitive.getAsString());
                return new JsonPrimitive(decoded);
            }
            return element;
        } else if (element.isJsonObject()) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                obj.add(entry.getKey(), decodeUnicodeInJson(entry.getValue()));
            }
            return obj;
        } else if (element.isJsonArray()) {
            JsonArray arr = new JsonArray();
            for (JsonElement e : element.getAsJsonArray()) {
                arr.add(decodeUnicodeInJson(e));
            }
            return arr;
        }
        return element;
    }

    /**
     * 解码字符串中的所有 Unicode 转义序列 XXXX 为真实字符
     * 支持连续转义、多重转义
     */
    private String decodeUnicodeEscapes(String str) {
        if (str == null || !str.contains("\\u")) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = str.length();
        while (i < len) {
            char c = str.charAt(i);
            // 匹配 XXXX 格式
            if (c == '\\' && i + 5 < len && str.charAt(i + 1) == 'u') {
                String hex = str.substring(i + 2, i + 6);
                try {
                    char ch = (char) Integer.parseInt(hex, 16);
                    sb.append(ch);
                    i += 6;
                    continue;
                } catch (NumberFormatException e) {
                    // 非合法十六进制，原样输出
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * XML 美化格式化（JDK 自带，缩进 2 空格）
     */
    private String formatXml(String xml) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty("indent", "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty("omit-xml-declaration", "no");

            StreamSource source = new StreamSource(new StringReader(xml));
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);
            return writer.toString().trim();
        } catch (Exception e) {
            return xml;
        }
    }


    // ==================== 日志裁剪（基于Document，高效不闪烁） ====================
    private void trimLog(JTextArea textArea) {
        Document doc = textArea.getDocument();
        Element root = doc.getDefaultRootElement();
        int lineCount = root.getElementCount();
        if (lineCount <= MAX_LOG_LINES) return;

        int excess = lineCount - MAX_LOG_LINES;
        Element lastRemoved = root.getElement(excess - 1);
        int removeLength = lastRemoved.getEndOffset();
        try {
            doc.remove(0, Math.min(removeLength, doc.getLength()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 文本变化监听 ====================
    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable callback;
        SimpleDocumentListener(Runnable callback) {
            this.callback = callback;
        }
        @Override
        public void insertUpdate(DocumentEvent e) { callback.run(); }
        @Override
        public void removeUpdate(DocumentEvent e) { callback.run(); }
        @Override
        public void changedUpdate(DocumentEvent e) { callback.run(); }
    }
}
