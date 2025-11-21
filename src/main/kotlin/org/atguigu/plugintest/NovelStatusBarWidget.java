package org.atguigu.plugintest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget.TextPresentation;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NovelStatusBarWidget implements StatusBarWidget, TextPresentation, Disposable {

    private static final Logger LOG = Logger.getInstance(NovelStatusBarWidget.class);
    private static NovelStatusBarWidget instance; // 静态实例，用于Action访问

    private final Project project;
    private String text = "📖 右键导入小说...";
    private List<String> lines = new ArrayList<>();
    private List<String> chapters = new ArrayList<>();
    private int index = 0;
    private boolean hidden = false;

    private final File booksFile = new File(System.getProperty("user.home"), ".novel_books.json");
    private final File bookmarksFile = new File(System.getProperty("user.home"), ".novel_bookmarks.json");

    private List<NovelRecord> books = new ArrayList<>();
    private Map<String, Map<String, Integer>> bookmarks = new HashMap<>();
    private NovelRecord currentBook = null;

    private final Gson gson = new Gson();

    private enum LastAction { LINE_TURN, CHAPTER_JUMP }
    private LastAction lastAction = LastAction.LINE_TURN;

    // 常用编码列表，供用户选择 (已增加 GB2312, GB18030, EUC-JP, EUC-KR, windows-1252 等)
    private static final String[] ENCODINGS = {
            "UTF-8",
            "GBK",
            "GB2312",
            "GB18030", // 简中常用
            "Big5",
            "Shift-JIS",
            "EUC-JP", // 日文常用
            "EUC-KR", // 韩文常用
            "ISO-8859-1",
            "windows-1252" // 西欧常用
    };

    public static class NovelRecord {
        String path;
        int lastIndex;
        // 存储导入时使用的编码，以便下次直接加载
        String encoding;

        public NovelRecord() {}
        public NovelRecord(String path, int lastIndex, String encoding) {
            this.path = path;
            this.lastIndex = lastIndex;
            this.encoding = encoding;
        }
    }

    public NovelStatusBarWidget(Project project) {
        this.project = project;
        instance = this;

        loadBooks();
        loadBookmarks();

        if (!books.isEmpty()) {
            currentBook = books.get(books.size() - 1);
            // 尝试使用上次保存的编码加载，如果失败则静默处理
            if (!importTxtFileWithEncoding(new File(currentBook.path), currentBook.encoding)) {
                text = "📖 无法使用上次编码加载：" + currentBook.encoding;
            }
        }
    }

    public static NovelStatusBarWidget getInstance() {
        return instance;
    }

    /**
     * 翻行操作，供 Action 调用。
     * @param forward true: 下一行, false: 上一行
     */
    public void turnLine(boolean forward) {
        if (lines.isEmpty() || hidden) return;

        // 章节跳转后，第一次翻页动作会直接跳到下一行，之后就进行正常的模运算
        if (lastAction == LastAction.CHAPTER_JUMP) {
            lastAction = LastAction.LINE_TURN;
        } else {
            index = forward ? (index + 1) % lines.size() : (index - 1 + lines.size()) % lines.size();
        }

        text = lines.get(index);
        saveCurrentBookProgress();
        updateStatusBar();
    }

    private void updateStatusBar() {
        StatusBar statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project);
        if (statusBar != null) statusBar.updateWidget(ID());
    }

    /**
     * 切换隐藏/显示状态，供 Action 调用。
     */
    public void toggleHidden() {
        hidden = !hidden;
        text = hidden ? "📚" : (lines.isEmpty() ? "📖 右键导入小说..." : lines.get(index));
        updateStatusBar();
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                turnLine(true);
            } else if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
                showMenu((Component) e.getSource(), e.getX());
            } else if (SwingUtilities.isRightMouseButton(e)) { // 增加右键处理
                showMenu((Component) e.getSource(), e.getX());
            }
        };
    }

    private void showMenu(Component parent, int x) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem importTxt = new JMenuItem("📥 导入TXT小说");
        importTxt.addActionListener(ev -> importTxtFile());

        JMenuItem switchBook = new JMenuItem("📚 切换书籍/删除");
        switchBook.addActionListener(ev -> showBookList());

        // 新增：切换编码按钮
        String currentEncoding = (currentBook != null) ? currentBook.encoding : "N/A";
        JMenuItem switchEncoding = new JMenuItem("⚙️ 切换当前编码 (" + currentEncoding + ")");
        switchEncoding.addActionListener(ev -> showEncodingSelector());

        JMenuItem saveBookmark = new JMenuItem("🔖 保存书签点");
        saveBookmark.addActionListener(ev -> saveBookmarkPoint());

        JMenuItem loadBookmark = new JMenuItem("📜 查看书签点");
        loadBookmark.addActionListener(ev -> showBookmarkList());

        JMenuItem chapterList = new JMenuItem("📖 章节列表");
        chapterList.addActionListener(ev -> showChapterList());

        menu.add(importTxt);
        menu.add(switchBook);
        menu.add(switchEncoding); // 添加切换编码按钮
        menu.add(saveBookmark);
        menu.add(loadBookmark);
        menu.addSeparator();
        menu.add(chapterList);

        menu.show(parent, x, -menu.getPreferredSize().height); // 在鼠标点击位置上方显示
    }

    /**
     * 显示编码选择器并切换当前书籍的编码。
     */
    private void showEncodingSelector() {
        if (currentBook == null) {
            JOptionPane.showMessageDialog(null, "请先导入小说！");
            return;
        }

        // 弹出选择框
        String selectedEncoding = (String) JOptionPane.showInputDialog(
                null,
                "当前编码: " + currentBook.encoding + "。\n请选择新的编码重新加载文件：",
                "切换小说编码",
                JOptionPane.QUESTION_MESSAGE,
                null,
                ENCODINGS,
                currentBook.encoding
        );

        if (selectedEncoding != null && !selectedEncoding.isEmpty() && !selectedEncoding.equals(currentBook.encoding)) {
            // 使用新编码重新加载文件
            if (importTxtFileWithEncoding(new File(currentBook.path), selectedEncoding)) {
                // 如果成功，更新 NovelRecord 中的编码信息
                currentBook.encoding = selectedEncoding;
                saveBooks();
                JOptionPane.showMessageDialog(null, "编码切换成功，已使用 " + selectedEncoding + " 重新加载。");
            } else {
                JOptionPane.showMessageDialog(null, "切换失败，使用 " + selectedEncoding + " 编码无法读取文件。", "切换失败", JOptionPane.ERROR_MESSAGE);
            }
        } else if (selectedEncoding != null && selectedEncoding.equals(currentBook.encoding)) {
            JOptionPane.showMessageDialog(null, "您选择了当前正在使用的编码，无需切换。");
        }
    }

    private void importTxtFile() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            // 默认尝试 UTF-8 编码加载
            if (importTxtFileWithEncoding(file, "UTF-8")) {
                handleSuccessfulImport(file, "UTF-8");
            } else {
                // 如果默认失败，弹出对话框让用户手动选择编码
                String selectedEncoding = (String) JOptionPane.showInputDialog(
                        null,
                        "UTF-8 编码读取失败，请选择正确的编码：",
                        "选择小说编码",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        ENCODINGS,
                        "GBK"
                );

                if (selectedEncoding != null && !selectedEncoding.isEmpty()) {
                    if (importTxtFileWithEncoding(file, selectedEncoding)) {
                        handleSuccessfulImport(file, selectedEncoding);
                    } else {
                        JOptionPane.showMessageDialog(null, "导入失败，使用 " + selectedEncoding + " 编码仍无法读取文件。");
                    }
                } else {
                    JOptionPane.showMessageDialog(null, "导入已取消。");
                }
            }
        }
    }

    private void handleSuccessfulImport(File file, String encoding) {
        // 使用 addOrUpdateBook 来添加新记录或更新现有记录的编码
        currentBook = addOrUpdateBook(file.getAbsolutePath(), encoding);
        saveBooks();
        JOptionPane.showMessageDialog(null, "导入成功，使用编码：" + encoding + "，共 " + lines.size() + " 行");
    }

    /**
     * 导入小说文本文件，使用指定的编码。
     * @param file 要导入的文件
     * @param encoding 指定的编码格式
     * @return 导入是否成功
     */
    private boolean importTxtFileWithEncoding(File file, String encoding) {
        if (file == null || !file.exists() || encoding == null) return false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), encoding))) {

            lines.clear();
            chapters.clear();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                lines.add(line);

                // 章节识别: 使用 lines.size() - 1 作为行号
                if (line.matches("第.{1,9}[章回节卷].*")) {
                    chapters.add(line + "（行号: " + (lines.size() - 1) + "）");
                }
            }

            if (!lines.isEmpty()) {
                // 尝试加载当前书籍的进度
                NovelRecord recordToLoad = books.stream()
                        .filter(b -> b.path.equals(file.getAbsolutePath()))
                        .findFirst().orElse(null);

                if (recordToLoad != null) {
                    index = Math.min(recordToLoad.lastIndex, lines.size() - 1);
                } else {
                    index = 0; // 新书或无记录
                }
                text = lines.get(index);
            } else {
                index = 0;
                text = "📖 小说为空";
            }
            LOG.info("Novel imported successfully with encoding: " + encoding + " from " + file.getAbsolutePath());
            return true; // 成功
        } catch (UnsupportedEncodingException ex) {
            LOG.error("Unsupported encoding: " + encoding, ex);
            text = "📖 无法识别的编码：" + encoding;
            return false;
        } catch (IOException ex) {
            LOG.warn("Failed to read file with encoding " + encoding + ": " + ex.getMessage());
            text = "📖 文件读取失败，请检查编码";
            return false;
        }
    }


    private NovelRecord addOrUpdateBook(String path, String encoding) {
        for (NovelRecord b : books) {
            if (b.path.equals(path)) {
                b.encoding = encoding; // 更新编码
                return b;
            }
        }
        NovelRecord newBook = new NovelRecord(path, 0, encoding);
        books.add(newBook);
        return newBook;
    }

    private void saveCurrentBookProgress() {
        if (currentBook != null) {
            currentBook.lastIndex = index;
            saveBooks();
        }
    }

    private void saveBooks() {
        try (FileWriter fw = new FileWriter(booksFile)) {
            gson.toJson(books, fw);
            LOG.info("Books saved to " + booksFile.getAbsolutePath());
        } catch (IOException ex) {
            LOG.warn("Failed to save books to " + booksFile.getAbsolutePath(), ex);
        }
    }

    private void loadBooks() {
        if (!booksFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(booksFile))) {
            Type type = new TypeToken<List<NovelRecord>>() {}.getType();
            List<NovelRecord> list = gson.fromJson(r, type);
            if (list != null) {
                // 确保旧版本数据兼容性，如果 encoding 字段缺失，默认设置为 UTF-8
                list.forEach(record -> {
                    if (record.encoding == null) record.encoding = "UTF-8";
                });
                books = list;
            }
            LOG.info("Books loaded from " + booksFile.getAbsolutePath());
        } catch (IOException ex) {
            LOG.warn("Failed to load books from " + booksFile.getAbsolutePath(), ex);
        }
    }

    private void saveBookmarks() {
        try (FileWriter fw = new FileWriter(bookmarksFile)) {
            gson.toJson(bookmarks, fw);
            LOG.info("Bookmarks saved to " + bookmarksFile.getAbsolutePath());
        } catch (IOException ex) {
            LOG.warn("Failed to save bookmarks to " + bookmarksFile.getAbsolutePath(), ex);
        }
    }

    private void loadBookmarks() {
        if (!bookmarksFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(bookmarksFile))) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
            Map<String, Map<String, Integer>> map = gson.fromJson(r, type);
            if (map != null) bookmarks = map;
            LOG.info("Bookmarks loaded from " + bookmarksFile.getAbsolutePath());
        } catch (IOException ex) {
            LOG.warn("Failed to load bookmarks from " + bookmarksFile.getAbsolutePath(), ex);
        }
    }

    // -------------------- 章节管理 --------------------
    private void showChapterList() {
        if (chapters.isEmpty()) {
            JOptionPane.showMessageDialog(null, "未识别到章节标题（请确保章节行以“第X章”开头）");
            return;
        }

        JList<String> list = new JList<>(chapters.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(400, chapters.size() * 25)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "章节列表", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JButton jumpButton = new JButton("跳转");
        jumpButton.addActionListener(e -> {
            int selected = list.getSelectedIndex();
            if (selected >= 0) jumpToChapter(selected);
            dialog.dispose();
        });

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int selected = list.getSelectedIndex();
                    if (selected >= 0) {
                        jumpToChapter(selected);
                        dialog.dispose();
                    }
                }
            }
        });

        dialog.add(jumpButton, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private void jumpToChapter(int selected) {
        if (selected < 0 || selected >= chapters.size()) return;
        String chapter = chapters.get(selected);
        int pos = extractLineNumber(chapter);

        // 定位到章节标题行
        if (pos >= 0 && pos < lines.size()) {
            index = pos; // 定位到章节标题行
            text = lines.get(index);
            lastAction = LastAction.CHAPTER_JUMP;
            saveCurrentBookProgress();
            updateStatusBar();
        } else {
            LOG.warn("Failed to jump to chapter. Extracted line number " + pos + " is out of bounds.");
        }
    }

    private int extractLineNumber(String chapter) {
        if (chapter == null) return -1;
        try {
            Pattern p = Pattern.compile("行号\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(chapter);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ex) {
            LOG.warn("Failed to extract line number from chapter string: " + chapter, ex);
        }
        return -1;
    }

    // -------------------- 书籍与书签逻辑 --------------------
    private void showBookList() {
        if (books.isEmpty()) {
            JOptionPane.showMessageDialog(null, "没有已导入的书籍");
            return;
        }

        // 列表中显示书籍名称和编码
        String[] bookNames = books.stream().map(b -> new File(b.path).getName() + " (" + b.encoding + ")").toArray(String[]::new);
        JList<String> list = new JList<>(bookNames);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 尝试定位到当前书籍
        int currentIdx = -1;
        if (currentBook != null) {
            for (int i = 0; i < books.size(); i++) {
                if (books.get(i).path.equals(currentBook.path)) {
                    currentIdx = i;
                    break;
                }
            }
        }
        if (currentIdx != -1) {
            list.setSelectedIndex(currentIdx);
        } else if (books.size() > 0) {
            list.setSelectedIndex(books.size() - 1);
        }


        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(300, bookNames.length * 30)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "切换书籍", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();

        JButton switchButton = new JButton("切换");
        switchButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < books.size()) {
                NovelRecord selectedBook = books.get(selectedIndex);
                // 切换时使用保存的编码加载
                if (importTxtFileWithEncoding(new File(selectedBook.path), selectedBook.encoding)) {
                    currentBook = selectedBook; // 切换成功才更新 currentBook
                    updateStatusBar();
                    dialog.dispose();
                } else {
                    JOptionPane.showMessageDialog(null, "切换书籍失败，无法读取文件。请尝试重新导入并指定正确的编码。");
                }
            }
        });
        buttonPanel.add(switchButton);

        JButton deleteButton = new JButton("删除");
        deleteButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < books.size()) {
                NovelRecord toDelete = books.remove(selectedIndex);
                if (currentBook != null && currentBook.path.equals(toDelete.path)) {
                    currentBook = null;
                    lines.clear();
                    chapters.clear();
                    index = 0;
                    text = "📖 右键导入小说...";
                }
                bookmarks.remove(toDelete.path); // 同时删除书签
                saveBooks();
                saveBookmarks();
                updateStatusBar();
                dialog.dispose();
            }
        });
        buttonPanel.add(deleteButton);

        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /**
     * 保存当前行位置的书签点。
     */
    private void saveBookmarkPoint() {
        if (currentBook == null) {
            JOptionPane.showMessageDialog(null, "请先导入小说！");
            return;
        }

        // 弹出输入框，要求用户输入书签名称
        String name = JOptionPane.showInputDialog(null, "请输入书签名称：", "保存书签", JOptionPane.QUESTION_MESSAGE);

        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(null, "书签名称不能为空。", "保存失败", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // 覆盖或新增书签
            bookmarks.computeIfAbsent(currentBook.path, k -> new HashMap<>()).put(name, index);
            saveBookmarks();
            // 增加行号提示
            JOptionPane.showMessageDialog(null, "书签点已保存！ (位置: 第 " + (index + 1) + " 行)", "保存成功", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * 显示书签列表并处理跳转和删除操作。
     */
    private void showBookmarkList() {
        if (bookmarks.isEmpty()) {
            JOptionPane.showMessageDialog(null, "没有保存的书签点");
            return;
        }

        List<String> displayList = new ArrayList<>();
        List<String> keys = new ArrayList<>();

        // 确保只显示存在于 books 列表中的书籍书签
        Set<String> validPaths = new HashSet<>();
        books.forEach(b -> validPaths.add(b.path));

        for (Map.Entry<String, Map<String, Integer>> entry : bookmarks.entrySet()) {
            String path = entry.getKey();
            if (!validPaths.contains(path)) continue; // 跳过已删除书籍的书签

            String fileName = new File(path).getName();
            for (Map.Entry<String, Integer> bm : entry.getValue().entrySet()) {
                // 行号从 1 开始显示
                displayList.add(fileName + " - " + bm.getKey() + " (行号: " + (bm.getValue() + 1) + ")");
                keys.add(path + "|" + bm.getKey());
            }
        }

        if (displayList.isEmpty()) {
            JOptionPane.showMessageDialog(null, "当前导入的书籍没有保存的书签点。");
            return;
        }


        JList<String> list = new JList<>(displayList.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(300, displayList.size() * 30)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "书签列表", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JButton goButton = new JButton("跳转");
        goButton.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) {
                String[] parts = keys.get(idx).split("\\|");
                String path = parts[0], bmName = parts[1];
                NovelRecord bookRecord = books.stream().filter(b -> b.path.equals(path)).findFirst().orElse(null);

                if (bookRecord != null) {
                    // 跳转前使用正确的编码重新加载书籍
                    if (importTxtFileWithEncoding(new File(bookRecord.path), bookRecord.encoding)) {
                        currentBook = bookRecord; // 确保切换到目标书籍
                        Integer bookmarkIndex = bookmarks.get(path).get(bmName);
                        if (bookmarkIndex != null && bookmarkIndex < lines.size() && bookmarkIndex >= 0) {
                            index = bookmarkIndex;
                            text = lines.get(index);
                            updateStatusBar();
                        } else {
                            LOG.warn("Bookmark index " + bookmarkIndex + " is invalid for book " + path);
                            JOptionPane.showMessageDialog(null, "书签行号无效，可能小说文件已被修改。");
                        }
                    }
                } else {
                    JOptionPane.showMessageDialog(null, "未找到该书籍文件，可能已被删除。");
                }
                dialog.dispose();
            }
        });

        JButton deleteButton = new JButton("删除书签");
        deleteButton.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) {

                String selectedBookmark = displayList.get(idx);
                // 增加删除确认，防止误操作
                int confirm = JOptionPane.showConfirmDialog(
                        null,
                        "确定要删除书签:\n" + selectedBookmark + " 吗？",
                        "确认删除",
                        JOptionPane.YES_NO_OPTION
                );

                if (confirm == JOptionPane.YES_OPTION) {
                    String[] parts = keys.get(idx).split("\\|");
                    String path = parts[0], bmName = parts[1];

                    Map<String, Integer> bookBookmarks = bookmarks.get(path);
                    if (bookBookmarks != null) {
                        bookBookmarks.remove(bmName);
                        if (bookBookmarks.isEmpty()) {
                            bookmarks.remove(path);
                        }
                        saveBookmarks();
                        // 刷新列表 (通过关闭并重新打开对话框实现)
                        dialog.dispose();
                        showBookmarkList();
                        JOptionPane.showMessageDialog(null, "书签已删除。");
                    }
                }
            }
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(goButton);
        buttonPanel.add(deleteButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    @Override
    public @NotNull String ID() { return "NovelStatusBarWidget"; }

    @Override
    public @Nullable WidgetPresentation getPresentation() { return this; }

    @Override
    public void install(@NotNull StatusBar statusBar) {}

    @Override
    public void dispose() {
        instance = null;
    }

    @Override
    public @NotNull String getText() { return text; }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT; // 左对齐
    }

    @Nullable
    @Override
    public String getTooltipText() { return "小说阅读器"; }
}
