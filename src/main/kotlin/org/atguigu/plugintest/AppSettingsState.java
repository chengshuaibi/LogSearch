package org.atguigu.plugintest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// @State 注解定义了配置文件的名称和存储位置
@State(
    name = "org.atguigu.plugintest.AppSettingsState",
    storages = @Storage("ExcelImporterSettings.xml") // 配置将保存在 IDEA 配置目录下的这个文件里
)
public class AppSettingsState implements PersistentStateComponent<AppSettingsState> {

    // 注意：需要保存的字段必须是 public，且不能是 final
    public DbType dbType = DbType.MYSQL;
    public String dbUrl = DbType.MYSQL.getDefaultUrl();
    public String dbUser = "root";
    public String tableName = "";
    // 密码不在此保存

    public static AppSettingsState getInstance() {
        // 获取应用程序全局服务实例
        return ApplicationManager.getApplication().getService(AppSettingsState.class);
    }

    @Override
    public @Nullable AppSettingsState getState() {
        // 返回自身实例，以便 IDEA 保存当前状态
        return this;
    }

    @Override
    public void loadState(@NotNull AppSettingsState state) {
        // 加载旧状态到当前实例
        XmlSerializerUtil.copyBean(state, this);
    }
}
