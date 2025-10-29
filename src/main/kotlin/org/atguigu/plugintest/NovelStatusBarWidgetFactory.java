package org.atguigu.plugintest;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NovelStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return "NovelStatusBarWidget";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "小说阅读器";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @Nullable StatusBarWidget createWidget(@NotNull Project project) {
        return new NovelStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {}

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
