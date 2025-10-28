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
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
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
                            ? "grep -ia " + quoteShell(keyword) + " " + env.getLogPath()
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
                            for (String part : splitAndFormatXml(line)) {
                                final String formatted = part;
                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        doc.insertString(doc.getLength(), formatted + "\n",
                                                hasKeyword ? keywordStyle : defaultStyle);
                                        trimLog(doc);
                                        logPane.setCaretPosition(doc.getLength());
                                    } catch (BadLocationException e) {
                                        e.printStackTrace();
                                    }
                                });
                            }
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
        logPane.setEditorKit(new StyledEditorKit() {
            @Override
            public ViewFactory getViewFactory() {
                return elem -> new WrappedPlainView(elem, true);
            }
        });

        JScrollPane scrollPane = new JScrollPane(logPane);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("环境:")); topPanel.add(envSelector);
        topPanel.add(new JLabel("关键词:")); topPanel.add(keywordField);
        topPanel.add(startButton); topPanel.add(stopButton);
        topPanel.add(addConfigButton); topPanel.add(editConfigButton);
        topPanel.add(deleteConfigButton); topPanel.add(clearLogButton);
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
            LogReaderContext context = new LogReaderContext(selected, keyword, logPane);
            contextHolder[0] = context;
            context.start();
        });

        stopButton.addActionListener(ev -> {
            if (contextHolder[0] != null && contextHolder[0].isRunning()) {
                contextHolder[0].stop();
                JOptionPane.showMessageDialog(null, "日志读取已停止");
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

    // 🔍 按 XML 声明分割并格式化
    private List<String> splitAndFormatXml(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || !line.contains("<?xml")) {
            result.add(line);
            return result;
        }

        String[] parts = line.split("(?=<\\?xml )");
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (part.trim().startsWith("<?xml")) {
                result.add(formatXml(part.trim()));
            } else {
                result.add(part);
            }
        }
        return result;
    }

    private String formatXml(String xmlText) {
        try {
            javax.xml.transform.Source xmlInput = new javax.xml.transform.stream.StreamSource(new StringReader(xmlText));
            StringWriter stringWriter = new StringWriter();
            javax.xml.transform.Result xmlOutput = new javax.xml.transform.stream.StreamResult(stringWriter);
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(xmlInput, xmlOutput);
            return "\n================ XML START ================\n" +
                    stringWriter.toString().trim() +
                    "\n================ XML END ==================\n";
        } catch (Exception e) {
            return xmlText;
        }
    }
}
