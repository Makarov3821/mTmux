package dev.mtmux

import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Documentation-only fixture driver. No real server, account, private key, microphone or ASR input. */
@RunWith(AndroidJUnit4::class)
open class UiCatalogTest {
    private val terminalRoot=javaClass.simpleName=="UiTerminalCatalogTest"
    val compose = if(terminalRoot) createAndroidComposeRule<MainActivity>() else createAndroidComposeRule<UiCatalogActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AppLocaleRule("zh-CN")).around(compose)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val output get() = File(compose.activity.getExternalFilesDir(null),"ui-catalog").apply {mkdirs()}
    private val records = JSONArray()
    private val pool = WarmConnectionPool<SshClient>(isReusable={it.isReusable()})
    private var dark = true
    private var palette = AppPalette.TOKYO
    private var revision = 0
    private var mainActivity:MainActivity?=null
    private val context:ComponentActivity get() = mainActivity ?: compose.activity
    private val store by lazy { ServerProfiles(instrumentation.targetContext) }
    private val first = ServerProfile(id="catalog-dev",name="开发服务器",host="192.0.2.10",port=22,user="developer",path="tmux",keyAuthentication=false)
    private val second = first.copy(id="catalog-build",name="构建节点",host="192.0.2.20",user="builder")
    private val third = first.copy(id="catalog-offline",name="备用服务器",host="192.0.2.30",user="operator")
    private lateinit var folder: ServerFolder
    private fun selected(group:String) = args.getString("catalogGroup").let {it==null || it==group}
    private fun writeProfiles(profiles:List<ServerProfile>) {
        val items=JSONArray()
        for(profile in profiles) items.put(JSONObject().put("id",profile.id).put("name",profile.name).put("host",profile.host)
            .put("port",profile.port).put("user",profile.user).put("path",profile.path).put("keyAuthentication",profile.keyAuthentication)
            .put("enableTmuxMouse",profile.enableTmuxMouse).put("jumps",JSONArray().also {a -> profile.jumps.forEach {a.put(it.json())}}))
        val edit=context.getSharedPreferences("server-profiles",0).edit().putString("profiles",items.toString())
        profiles.forEach {edit.remove("credential:${it.id}")}
        check(edit.commit())
    }
    private fun seed(empty:Boolean=false) {
        compose.runOnUiThread {
            val wanted=if(empty) emptyList() else listOf(first,second,third)
            writeProfiles(wanted)
            wanted.forEach {ServerCache(context).collapse(it.id,false)}
            val organization=ServerOrganization(context)
            organization.folders().forEach {organization.deleteFolder(it.id)}
            context.getSharedPreferences("terminal-settings",0).edit().putString("appearance",if(dark) "DARK" else "LIGHT")
                .putString("palette",palette.name).putString("ssh-retention","MEDIUM").commit()
            PrivacyDisplay(context).enabled(true)
            if(!empty) {
                listOf(first,second,third).forEach {profile ->
                    ServerCache(context).markAttempt(profile)
                }
                folder=organization.saveFolder(name="日常开发")
                organization.move(first.id,folder.id);organization.move(second.id,folder.id)
                val tasks=listOf("code-review" to TaskState.ATTENTION,"api-service" to TaskState.RUNNING,"daily-check" to TaskState.COMPLETED).mapIndexed {i,(name,state) ->
                    val task=RecentTask(first.id,first.host,first.port,first.user,first.path,"catalog-public-pin","100:200:\$0:@$i:%$i:${300+i}",name,"$i · $name")
                    task to state
                }
                ServerCache(context).write(first,tasks.map {it.first},tasks.associate {it.first.identity!! to it.second})
                val task=RecentTask(second.id,second.host,second.port,second.user,second.path,"catalog-public-pin","101:201:\$1:@0:%0:400","release-build","0 · build")
                ServerCache(context).write(second,listOf(task),mapOf(task.identity!! to TaskState.RUNNING))
                ServerCache(context).write(third,emptyList())
            }
        }
    }
    private fun render(content:@Composable () -> Unit) {
        val id=++revision
        compose.runOnUiThread {
            // MainActivity's terminal sits behind Home. Dispose it before replacing the
            // composition so an orphaned renderer cannot kill this documentation process.
            findTerminal(context.window.decorView)?.let {old ->
                old.dispose()
                MainActivity::class.java.getDeclaredField("terminalView").apply {isAccessible=true}.set(context,null)
            }
            context.setContent {
                key(id) {
                    MaterialTheme(colorScheme=appColors(dark,palette),typography=appTypography,shapes=appShapes) {
                        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {content()}
                    }
                }
            }
            context.window.statusBarColor=AndroidColor.TRANSPARENT
            context.window.navigationBarColor=AndroidColor.TRANSPARENT
            WindowCompat.getInsetsController(context.window,context.window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
        }
        compose.waitForIdle()
    }
    private fun home(empty:Boolean=false) {
        seed(empty)
        homeContent()
    }
    private fun homeContent() {
        render {ServerHome(pool,privacyDisplay=true,onPrivacyDisplay={},refreshOnEntry=false,onEntryHandled={},onOpen={_,_,_->},
            onPreferences={_,_,_->},appearance=if(dark) AppAppearance.DARK else AppAppearance.LIGHT,onAppearance={},palette=palette,onPalette={})}
    }
    private fun editor(profile:ServerProfile?=null) {
        render {ServerEditor(store,profile,pool,onClose={},onSaved={_,_->})}
    }
    private fun settings() {
        render {AppSettings({},pool,if(dark) AppAppearance.DARK else AppAppearance.LIGHT,{},palette,{},true,{},14,{},1f,{},true,{})}
    }
    private fun click(tag:String) {if(!terminalRoot) runCatching {compose.onNodeWithTag(tag).performScrollTo()};compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) {it()};if(terminalRoot) compose.mainClock.advanceTimeBy(500) else compose.waitForIdle()}
    private fun type(tag:String,value:String) {runCatching {compose.onNodeWithTag(tag).performScrollTo()};compose.onNodeWithTag(tag).performTextReplacement(value);compose.waitForIdle()}
    private fun text(id:Int)=context.getString(id)
    private fun shot(group:String,name:String,title:String,kind:String="production-ui",source:String="",notes:String="",keepKeyboard:Boolean=false) {
        if(terminalRoot) compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        if(terminalRoot) {compose.mainClock.advanceTimeBy(500);compose.waitForIdle()}
        if(mainActivity!=null) syncTerminalFixture()
        if(!keepKeyboard) {
            compose.runOnUiThread {
                val ime=context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                android.view.inspector.WindowInspector.getGlobalWindowViews().filter {it.isShown}.forEach {ime.hideSoftInputFromWindow(it.windowToken,0)}
            }
            SystemClock.sleep(350)
        }
        // Only this fresh catalog AVD contains these public demo fixtures. Production capture policy is unchanged.
        compose.runOnUiThread {
            context.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter {it.isShown}.forEach {view ->
                val params=view.layoutParams as? WindowManager.LayoutParams
                if(params!=null && params.flags and WindowManager.LayoutParams.FLAG_SECURE!=0) {
                    params.flags=params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    runCatching {(context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(view,params)}
                }
            }
        }
        SystemClock.sleep(650)
        var canvasBitmap:Bitmap?=null
        compose.runOnUiThread {
            val decor=context.window.decorView
            canvasBitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(canvasBitmap!!)
            canvas.drawColor(AndroidColor.rgb(19,20,28))
            val origin=IntArray(2);decor.getLocationOnScreen(origin)
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter {it.isShown}.forEach {view ->
                val params=view.layoutParams as? WindowManager.LayoutParams
                if(view!==decor && params!=null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND!=0)
                    canvas.drawColor(AndroidColor.argb((params.dimAmount*255).toInt(),0,0,0))
                val xy=IntArray(2);view.getLocationOnScreen(xy)
                canvas.save();canvas.translate((xy[0]-origin[0]).toFloat(),(xy[1]-origin[1]).toFloat())
                view.draw(canvas);canvas.restore()
            }
        }
        val bitmap=requireNotNull(canvasBitmap)
        val file=File(output,"$group/$name.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        records.put(JSONObject().put("id","$group/$name").put("title",title).put("file","$group/$name.png")
            .put("kind",if(terminalRoot && group=="terminal" && !name.contains("copy")) "terminal-render-fixture" else kind).put("captureMethod","android-view-canvas").put("source",source).put("notes",notes).put("theme",if(dark) "dark" else "light")
            .put("palette",palette.name).put("width",bitmap.width).put("height",bitmap.height))
        bitmap.recycle()
        File(output,"manifest.json").writeText(JSONObject().put("sourceCommit",args.getString("catalogSource") ?: "working-tree").put("appVersion",BuildConfig.VERSION_NAME)
            .put("device","Android 15 AOSP ATD, dedicated software emulator, synthetic fixtures")
            .put("capturePolicy","Capture protection is cleared only by this documentation test on a fresh fixture AVD; release code is unchanged.")
            .put("screens",records).toString(2))
        File(output,"manifest-${args.getString("catalogGroup") ?: if(terminalRoot) "terminal" else "all"}.json").writeText(File(output,"manifest.json").readText())
        println("CATALOG_CAPTURE $group/$name")
    }
    @Test fun captureCatalog() {
        org.junit.Assume.assumeTrue("Documentation capture is opt-in",args.getString("uiCatalog")=="true")
        require(BuildConfig.DEBUG && android.os.Build.HARDWARE in listOf("ranchu","goldfish")) {"Use a dedicated debug emulator"}
        require(store.all().all {it.id.startsWith("catalog-")}) {"Refusing to replace non-catalog server profiles"}
        try {
            if(terminalRoot) {captureTerminal();return}
            if(selected("home")) captureHome()
            if(args.getString("catalogSmoke")=="true") return
            if(selected("server")) captureServer()
            if(selected("settings")) captureSettings()
            if(selected("dialogs")) captureDialogs()
            if(selected("voice")) captureVoice()
            if(selected("extras")) captureExtras()
            if(selected("doctor")) captureDoctor()
        } finally {pool.close()}
    }
    private fun captureDoctor() {
        home()
        render { Backdrop(); TmuxDoctorInvitation({}, {}) }
        shot("doctor","01-invitation","保存验证后：可选 tmux 使用诊断",source="TmuxDoctorDialog.kt")
        val healthy=mapOf("path.status" to "ok","path" to "/usr/bin/tmux","version.client" to "tmux 3.4",
            "version.server" to "3.4","server.status" to "ready","server.frame" to "3:123",
            "sessions.sampled" to "2","mouse" to "on","history-limit" to "10000")
        for((name,facts) in listOf("02-report" to healthy,"03-old-version" to (healthy+mapOf("version.client" to "tmux 3.1c","version.server" to "3.1c")),
            "04-missing" to (healthy+mapOf("path.status" to "missing","server.status" to "none")),
            "05-mouse-off" to (healthy+mapOf("mouse" to "off")))) {
            render {
                Backdrop()
                AppSheet(onDismiss={},floating=false) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal=8.dp)) {
                        SheetHeader(stringResource(R.string.doctor_title),"开发服务器 · "+stringResource(R.string.doctor_checked,"10-30 20:37:00"))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=4.dp)) {
                            DoctorFindings(TmuxDoctorReport(facts),autoMouse=false,collapsePassing=true)
                        }
                        Row(Modifier.padding(top=10.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TonalActionButton(DialogAction(stringResource(R.string.common_close),{}),Modifier.weight(1f))
                            PrimaryActionButton(DialogAction(stringResource(R.string.doctor_recheck),{}),Modifier.weight(1f))
                        }
                    }
                }
            }
            shot("doctor",name,"tmux 诊断：$name","component-fixture","TmuxDoctorDialog.kt / DoctorFindings","生产诊断行组件，使用固定报告，不执行远端检查。")
        }
    }
    @Test fun captureDoctorConfig() {
        org.junit.Assume.assumeTrue("Documentation capture is opt-in",args.getString("uiCatalog")=="true" && args.getString("catalogGroup")=="doctor-config")
        require(BuildConfig.DEBUG && android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        require(store.all().all {it.id.startsWith("catalog-")})
        home()
        render { Backdrop(); DoctorConfigPreview("/home/developer","10000",{},true,{},{},{}) }
        shot("doctor","06-config-preview","首次使用：最小配置预览与确认","state-fixture","TmuxDoctorDialog.kt / TmuxConfigBootstrap.kt","固定预览；模板由生产代码生成，不写入远端文件。")
        render { Backdrop(); DoctorUndoDialog({},{}) }
        shot("doctor","07-undo","撤销初始化确认","state-fixture","TmuxDoctorDialog.kt","固定确认分支，不执行撤销。")
    }
    private fun captureHome() {
        home(true);shot("home","01-empty","首次打开：空服务器列表",source="ServerHome.kt")
        home();shot("home","02-populated","主界面：分组、服务器、任务状态",source="ServerHome.kt")
        if(args.getString("catalogSmoke")=="true") return
        click("server-sort");shot("home","03-sort-menu","服务器排序菜单",source="ServerHome.kt")
        home();type("server-search","code-review");shot("home","04-search-results","本地搜索：匹配任务",source="ServerHome.kt")
        home();type("server-search","没有这个任务");shot("home","05-search-empty","搜索无结果",source="ServerHome.kt")
        home();click("folder-${folder.id}");shot("home","06-folder-collapsed","分组折叠",source="ServerHome.kt")
        home();click("add-folder");shot("home","07-folder-new","新建分组弹窗",source="ServerHome.kt")
        home();click("more-${first.id}");shot("home","08-server-menu","服务器更多操作",source="ServerHome.kt")
        home();click("folder-menu-${folder.id}");shot("home","10-folder-menu","分组操作菜单",source="ServerHome.kt")
        compose.onNodeWithText(text(R.string.home_rename_folder)).performSemanticsAction(SemanticsActions.OnClick) {it()};shot("home","11-folder-rename","重命名分组",source="ServerHome.kt")
        home();click("folder-menu-${folder.id}");compose.onNodeWithText(text(R.string.home_delete_folder)).performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("home","12-folder-delete","删除分组确认",source="ServerHome.kt")
        home();click("more-${first.id}");compose.onNodeWithText(text(R.string.home_move_to_folder)).performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("home","13-move-server","移动服务器到分组",source="ServerHome.kt")
        home();click("more-${first.id}");compose.onNodeWithText(text(R.string.home_delete_server)).performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("home","14-delete-server","删除服务器确认",source="ServerHome.kt")
        home();compose.runOnUiThread {ServerCache(context).collapse(first.id,true)};homeContent()
        shot("home","15-server-collapsed","服务器任务列表折叠",source="ServerHome.kt")
        for(p in AppPalette.entries) {
            palette=p;home();shot("home","theme-${p.name.lowercase()}","主界面主题：${p.name}",source="ServerHome.kt / AppAppearance.kt")
        }
        palette=AppPalette.TOKYO;dark=false;home();shot("home","09-light","主界面：浅色",source="ServerHome.kt")
        dark=true
    }
    private fun captureServer() {
        home();editor();shot("server","01-add-key","添加服务器：私钥认证",source="ServerEditor.kt")
        compose.onNodeWithText(text(R.string.editor_auth_password),substring=false).performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("server","02-add-password","添加服务器：密码认证",source="ServerEditor.kt")
        editor(first);shot("server","03-edit-password","编辑服务器：密码认证",source="ServerEditor.kt")
        click("advanced");compose.onNodeWithTag("server-tmux-path").performScrollTo()
        shot("server","04-advanced","高级设置：分组、Jump Host、tmux 路径与鼠标",source="ServerEditor.kt")
        click("editor-folder");shot("server","05-folder-picker","服务器所属分组菜单",source="ServerEditor.kt")
        editor();click("save-server");shot("server","06-validation","保存校验失败：目标信息不完整",source="ServerEditor.kt")
        editor();type("profile-name","尚未保存的服务器")
        compose.onNodeWithTag("cancel-edit").performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("server","07-abandon","放弃未保存的修改",source="ServerEditor.kt")
        render {Backdrop(true);PrivateKeyPasteDialog({}, {})};shot("server","08-key-paste","粘贴 SSH 私钥",source="PrivateKeyPasteDialog.kt")
        editor(first);click("advanced");click("jump-enabled");click("jump-from-saved")
        shot("server","09-import-jump","从已保存服务器导入跳板",source="ServerEditor.kt")
        val via=first.copy(id="catalog-via",name="经跳板访问",jumps=listOf(JumpHost(host="192.0.2.40",port=22,user="relay",keyAuthentication=false)))
        compose.runOnUiThread {writeProfiles(store.all()+via)}
        editor(via);click("advanced");compose.onNodeWithTag("jump-1-host").performScrollTo()
        shot("server","10-jump-expanded","Jump Host 认证与路由编辑",source="ServerEditor.kt")
        render {Backdrop();NewTmuxDialog(first,pool,{}, {}, {_,_->})};shot("server","11-new-tmux","新建独立命名 tmux 会话",source="NewTmuxDialog.kt")
        type("new-tmux-name","bad.name");click("create-tmux")
        shot("server","12-new-tmux-invalid","新会话名称不合法",source="NewTmuxDialog.kt")
    }
    private fun captureSettings() {
        home();settings();shot("settings","01-top","设置：外观、主题与语言",source="AppSettings.kt")
        compose.onNodeWithTag("settings-speed").performScrollTo();shot("settings","02-terminal","设置：SSH 保留、终端显示与滚动倍率",source="AppSettings.kt")
        compose.onNodeWithTag("privacy-display").performScrollTo();shot("settings","03-privacy-voice","设置：隐私与本地语音",source="AppSettings.kt / VoiceIntegration.kt")
        compose.onNodeWithTag("export-debug-log").performScrollTo();shot("settings","04-logs","设置：诊断日志导出与清除",source="DebugLogSettings.kt")
        dark=false;settings();shot("settings","05-light","设置：浅色主题",source="AppSettings.kt");dark=true
    }

    private fun captureExtras() {
        home()
        compose.runOnUiThread {
            val cache=ServerCache(context);val snapshot=cache.read(first)!!
            val extra=snapshot.tasks.first().copy(identity="100:200:\$0:@0:%9:909")
            cache.write(first,snapshot.tasks+extra,snapshot.states+mapOf(extra.identity!! to TaskState.COMPLETED))
        }
        homeContent();shot("home","16-multiple-panes","同一 tmux 窗口包含多个 pane",source="ServerHome.kt")
        click("task-${first.id}-%0");shot("home","17-panes-expanded","展开窗口：逐个选择 pane",source="ServerHome.kt")
        home();compose.runOnUiThread {ServerCache(context).clearTaskStates(first)};homeContent()
        shot("home","18-unknown-states","任务状态：灰色未知 / 已过期",source="ServerHome.kt")
        home();compose.runOnUiThread {
            val task=ServerCache(context).read(first)!!.tasks.first()
            context.getSharedPreferences("server-profiles",0).edit().putString("recent-tasks",JSONArray().put(task.encode()).toString()).commit()
        };homeContent();shot("home","19-last-used","最近使用的任务标记",source="ServerHome.kt")
        home();click("add-folder");type("folder-name","日常开发")
        compose.onNodeWithText(text(R.string.common_save)).performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("home","20-folder-duplicate","新建分组：重名校验",source="ServerHome.kt")
        render {Backdrop(true);PrivateKeyPasteDialog({}, {})};type("private-key-text","not-a-private-key");click("confirm-key-paste")
        shot("server","13-private-key-invalid","粘贴私钥：格式错误提示",source="PrivateKeyPasteDialog.kt")
        settings();compose.onNodeWithTag("clear-debug-log").performScrollTo();click("clear-debug-log")
        shot("settings","06-log-cleared","诊断日志清除结果",source="DebugLogSettings.kt")
        render {TerminalCopyDialog(TerminalSnapshot("",false,0),14,true,{})}
        shot("terminal","20-copy-empty","复制窗口：暂无终端文字","component-fixture","TerminalCopyDialog.kt","固定空缓冲，展示禁用的复制按钮。")
        render {TerminalCopyDialog(TerminalSnapshot("演示终端输出\n\n这里只展示裁剪提示的界面状态。",true,0),14,true,{})}
        shot("terminal","21-copy-clipped","复制窗口：内容裁剪提示","component-fixture","TerminalCopyDialog.kt","固定裁剪标志，不代表实际生成了二十万字终端输出。")
        render {TerminalFixture()}
        shot("terminal","22-standard-controls","普通版终端：没有语音按钮的底栏","component-fixture","TerminalActions.kt","真实底栏组件，正文为测试文字排版，展示普通版双按钮布局。")
    }

    @Composable private fun Backdrop(editor:Boolean=false) {
        if(editor) ServerEditor(store,first,pool,onClose={},onSaved={_,_->})
        else ServerHome(pool,privacyDisplay=true,onPrivacyDisplay={},refreshOnEntry=false,onEntryHandled={},onOpen={_,_,_->},
            onPreferences={_,_,_->},appearance=AppAppearance.DARK,onAppearance={},palette=palette,onPalette={})
    }
    @Composable private fun TerminalFixture(voice:VoiceHoldState?=null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                AppIconButton(UiSymbol.BACK,"返回",{})
                Text("开发服务器 · code-review",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                AppIconButton(UiSymbol.KEYBOARD,"键盘",{})
                AppIconButton(UiSymbol.EDIT,"编辑文本",{})
                AppIconButton(UiSymbol.LATEST,"回到底端",{})
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("● Code Review Agent",color=MaterialTheme.colorScheme.primary,fontFamily=FontFamily.Monospace)
                Text("已完成连接池与终端输入检查。\n\n✓ SSH 连接可复用\n✓ 当前任务身份已确认\n✓ 测试通过，等待下一条指令",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text("› 请检查最新修改并运行测试",fontFamily=FontFamily.Monospace)
                Text("  Enter 发送 · Esc 取消",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TerminalKeys(true,0,{},{})
            TerminalActions(true,{}, {}) {
                if(voice!=null) VoiceHoldButton(voice,true,Modifier, {null},{_,_->},{},{},{})
            }
        }
    }
    private fun dialog(name:String,title:String,body:String,confirm:String=text(R.string.common_close),
        dismiss:String?=null,progress:Boolean=false,editor:Boolean=false,shield:Boolean=false) {
        render {
            Backdrop(editor)
            AlertDialog(onDismissRequest={},title={Text(title)},icon=if(shield) ({AppIcon(UiSymbol.SHIELD)}) else null,
                text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(progress) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(body)
                }},confirmButton={if(confirm.isNotEmpty()) TextButton({}) {Text(confirm)}},dismissButton=if(dismiss==null) null else ({TextButton({}) {Text(dismiss)}}))
        }
        shot("dialogs",name,title,"state-fixture",if(editor) "ServerVerification.kt" else "ServerHome.kt / MainActivity.kt",
            "瞬时状态固定预览：使用当前资源文案与原生 Material 组件复现对应私有分支，不进行网络认证。")
    }
    private fun captureDialogs() {
        home()
        fun host(name:String,endpoint:String,fp:String,changed:Boolean,editor:Boolean,subtitle:String?=null,step:String?=null) {
            render {
                Backdrop(editor)
                HostKeyDialog(HostKeyChallenge(endpoint,"catalog-key",fp,changed),{},{},
                    trustLabel=if(editor) stringResource(R.string.editor_trust_continue) else null,subtitle=subtitle,
                    headerEnd=step?.let { { Surface(shape=androidx.compose.foundation.shape.CircleShape,color=MaterialTheme.colorScheme.surfaceVariant) {
                        Text(it,Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium) } } },
                    route=if(step!=null) listOf("手机" to RouteState.NORMAL,"跳板 1" to (if(endpoint.contains("40")) RouteState.CURRENT else RouteState.DONE),"目标服务器" to (if(endpoint.contains("40")) RouteState.NORMAL else RouteState.CURRENT)) else null)
            }
            shot("dialogs",name,text(if(changed) R.string.trust_changed_title else R.string.trust_verify_title),"state-fixture",if(editor) "ServerVerification.kt" else "ServerHome.kt / MainActivity.kt",
                "瞬时状态固定预览：使用生产指纹组件，不进行网络认证。")
        }
        host("01-host-first","[192.0.2.10]:22","SHA256:ZgNwJk7mDemoFingerprintForUiCatalogOnly",false,false)
        host("02-host-changed","[192.0.2.10]:22","SHA256:ZgNwJk7mDemoFingerprintForUiCatalogOnly",true,false)
        render { Backdrop(true); VerificationPanel(listOf("跳板 1","目标服务器"),1,null,false,"目标服务器 · 正在连接并验证身份…",{},{},{}) }
        shot("dialogs","03-server-checking",text(R.string.editor_verifying_title),"state-fixture","ServerVerification.kt","固定验证进度，不进行网络认证。")
        host("04-server-fingerprint","[192.0.2.10]:22","SHA256:ZgNwJk7mDemoFingerprintForUiCatalogOnly",false,true,"正在验证目标服务器\n"+text(R.string.trust_verify_hint),"第 2 / 2 台")
        host("05-jump-fingerprint","[192.0.2.40]:22","SHA256:DemoJumpHostFingerprintOnly",false,true,"正在验证跳板 1\n"+text(R.string.trust_verify_hint),"第 1 / 2 台")
        render { Backdrop(true); VerificationPanel(listOf("跳板 1","目标服务器"),1,"目标服务器：连接超时",false,"",{},{},{}) }
        shot("dialogs","06-save-anyway",text(R.string.editor_check_failed),"state-fixture","ServerVerification.kt","固定失败状态，展示重试 / Save anyway / 取消。")
        render { Backdrop(); NewTmuxPanel("开发服务器","3.4","code-review",{},true,null,{},{}) }
        shot("dialogs","07-tmux-creating",text(R.string.tmux_creating),"state-fixture","NewTmuxDialog.kt","固定创建进行中，不执行远端命令。")
        for((name,message) in listOf("08-tmux-duplicate" to R.string.tmux_name_exists,"09-tmux-uncertain" to R.string.tmux_create_failed)) {
            render { Backdrop(); NewTmuxPanel("开发服务器","3.4","code-review",{},false,stringResource(message),{},{}) }
            shot("dialogs",name,text(message),"state-fixture","NewTmuxDialog.kt","固定远端响应分支，不进行 SSH 连接。")
        }
        render { TerminalFixture(); AppDialog(onDismiss={},title=stringResource(R.string.quick_reply_keep_title),body=stringResource(R.string.quick_reply_keep_body),icon=UiSymbol.REPLY,
            confirm=DialogAction(stringResource(R.string.quick_reply_append),{}),dismiss=DialogAction(stringResource(R.string.quick_reply_replace),{}),
            extra=DialogAction(stringResource(R.string.common_cancel),{})) }
        shot("dialogs","10-reply-conflict","快捷回复：追加、替换或取消","state-fixture","MainActivity.kt","固定已有本地草稿的确认分支。")
    }
    @OptIn(ExperimentalMaterial3Api::class)
    private fun captureVoice() {
        for((name,title,elapsed,cancel) in listOf(
            VoiceCase("01-preparing","语音：麦克风准备中",-1,false),
            VoiceCase("02-recording","语音：录音与绿色倒计时",12_000,false),
            VoiceCase("03-last-ten","语音：最后十秒变橙",51_000,false),
            VoiceCase("04-last-five","语音：即将结束变红",56_000,false),
            VoiceCase("05-cancel","语音：上滑松手取消",15_000,true))) {
            val state=VoiceHoldState();val id=state.begin()!!
            if(elapsed>=0) state.ready(id,SystemClock.elapsedRealtime()-elapsed)
            if(cancel) state.move(id,true)
            render {TerminalFixture(state);VoiceRecordingFeedback(state)}
            shot("voice",name,title,"component-fixture","VoiceHoldButton.kt / VoiceRecordingFeedback","使用真实录音组件固定倒计时状态，不启动麦克风。")
        }
        fun sheet(name:String,title:String,error:Int?=null) {
            render {
                TerminalFixture(VoiceHoldState())
                when (error) {
                    null -> VoiceTranscribingBubble {}
                    R.string.voice_error_permission -> AppSheet(onDismiss={}) {
                        SheetHeader(stringResource(R.string.voice_title),stringResource(R.string.voice_local_badge),UiSymbol.MIC,Tone.WARNING)
                        Text(stringResource(error),Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TonalActionButton(DialogAction(stringResource(R.string.common_close),{}),Modifier.weight(1f))
                            PrimaryActionButton(DialogAction(stringResource(R.string.voice_open_settings),{}),Modifier.weight(1f))
                        }
                    }
                    else -> VoiceErrorHint(stringResource(error)) {}
                }
            }
            shot("voice",name,title,"state-fixture","VoiceIntegration.kt","转写/错误分支固定展示，不运行识别模型。")
        }
        sheet("06-transcribing","语音：本机转写中")
        for((index,error) in listOf(R.string.voice_error_permission,R.string.voice_error_audio,R.string.voice_error_too_short,
            R.string.voice_error_no_speech,R.string.voice_error_model,R.string.voice_error_target_changed,R.string.voice_error_generic).withIndex())
            sheet("error-${index+1}",text(error),error)
        dark=false
        val state=VoiceHoldState();val id=state.begin()!!;state.ready(id,SystemClock.elapsedRealtime()-12_000)
        render {TerminalFixture(state);VoiceRecordingFeedback(state)}
        shot("voice","07-light-recording","语音录音：浅色主题","component-fixture","VoiceHoldButton.kt","真实录音反馈组件，演示状态。")
        dark=true
    }
    private data class VoiceCase(val name:String,val title:String,val elapsed:Long,val cancel:Boolean)
    private fun findTerminal(view:View):TerminalView? {
        if(view is TerminalView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findTerminal(view.getChildAt(i))?.let {return it}
        return null
    }
    private fun terminal():TerminalView {
        var result:TerminalView?=null
        compose.runOnUiThread {result=findTerminal(context.window.decorView)}
        return requireNotNull(result)
    }
    @Suppress("UNCHECKED_CAST") private fun state(name:String,value:Any?) {
        val field=MainActivity::class.java.getDeclaredField(name+"\$delegate").apply {isAccessible=true}
        when(val holder=field.get(context)) {
            is MutableLongState -> holder.longValue=value as Long
            is MutableIntState -> holder.intValue=value as Int
            is MutableState<*> -> (holder as MutableState<Any?>).value=value
            else -> error("Unsupported fixture state: $name")
        }
    }
    private var terminalDrawable:BitmapDrawable?=null
    private fun syncTerminalFixture() {
        val view=runCatching {terminal()}.getOrNull() ?: return
        val rect=runCatching {compose.onNodeWithTag("terminal-panel").fetchSemanticsNode().boundsInRoot}.getOrNull() ?: return
        compose.runOnUiThread {
            fun focused(root:View):Boolean {
                if(root is DirectTerminalInput) return root.hasFocus()
                return root is ViewGroup && (0 until root.childCount).any {focused(root.getChildAt(it))}
            }
            val offset=if(focused(context.window.decorView)) (view.height-rect.height.toInt()).coerceAtLeast(0) else 0
            terminalDrawable?.setBounds(0,-offset,view.width,view.height-offset)
            view.invalidate()
        }
    }
    private var mainAvailable=false
    private var mainDark=true
    private fun dismissTerminalOverlays() {
        repeat(6) {
            when {
                compose.onAllNodesWithTag("close-terminal-copy").fetchSemanticsNodes().isNotEmpty() -> click("close-terminal-copy")
                compose.onAllNodesWithTag("close-text-editor").fetchSemanticsNodes().isNotEmpty() -> click("close-text-editor")
                compose.onAllNodesWithText(text(R.string.common_cancel)).fetchSemanticsNodes().isNotEmpty() -> compose.onAllNodesWithText(text(R.string.common_cancel)).onLast().performSemanticsAction(SemanticsActions.OnClick) {it()}
                compose.onAllNodesWithText(text(R.string.common_close)).fetchSemanticsNodes().isNotEmpty() -> {
                    val close=compose.onAllNodesWithText(text(R.string.common_close)).onLast()
                    runCatching {close.performScrollTo()};close.performSemanticsAction(SemanticsActions.OnClick) {it()}
                }
                else -> return
            }
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
        }
    }
    private fun mainTerminal() {
        compose.runOnUiThread {
            context.getSharedPreferences("terminal-settings",0).edit().putString("appearance",if(dark) "DARK" else "LIGHT").putString("palette",palette.name).commit()
        }
        if(!mainAvailable || mainDark!=dark) {
            require(terminalRoot)
            if(mainAvailable && mainDark!=dark) {
                compose.runOnUiThread {compose.activity.recreate()}
                mainActivity=null
                compose.waitForIdle()
            }
            mainActivity=compose.activity as MainActivity
            compose.runOnUiThread {state("showConfig",false)}
            compose.waitForIdle()
            mainAvailable=true;mainDark=dark
        } else dismissTerminalOverlays()
        val view=terminal()

        compose.runOnUiThread {
            MainActivity::class.java.getDeclaredField("activeProfile").apply {isAccessible=true}.set(context,first)
            state("showConfig",false);state("wireToken","catalog-terminal");state("busy",false);state("challenge",null)
            state("terminalReady",true)
            state("target","developer@192.0.2.10:22");state("targetSession","code-review")
            state("connectedSession","\$0");state("replyPane",PaneBinding.parse("\$0","100:200:\$0:@0:%0:300"))
            state("panes",listOf(Pane("\$0","@0","%0",true,"code-review","Agent","0")))
            state("draftScopeKey","catalog/agent");state("status",TerminalStatus(uiText(R.string.status_connected_tmux)))
            state("renderedBytes",300L)
            state("reconnectTarget",TaskConnection(first,ServerCache(context).read(first)?.tasks?.firstOrNull()))
            // The software emulator's renderer is unstable. Keep the actual native UI and
            // inject a bitmap rendered from the same bundled xterm source for documentation.
            view.dispose()
            val bytes=instrumentation.context.assets.open("ui-catalog/terminal-${if(dark) "dark" else "light"}.png")
            val bitmap=bytes.use {BitmapFactory.decodeStream(it)}
            view.overlay.clear()
            terminalDrawable=BitmapDrawable(context.resources,bitmap)
            view.overlay.add(terminalDrawable!!)

        }
        syncTerminalFixture()
        compose.waitForIdle()
    }
    private fun captureTerminal() {
        seed()
        mainTerminal();shot("terminal","01-agent","终端：Agent 界面与直接输入","production-ui","MainActivity.kt / TerminalView.kt","真实终端组件与演示输出；无远端连接。")
        for(i in 1..3) {
            compose.onNodeWithTag("terminal-key-pages").performSemanticsAction(SemanticsActions.ScrollToIndex) {it(TERMINAL_KEY_PAGER_START+i)}
            shot("terminal","keys-$i",listOf("F1–F6 快捷键","F7–F12 快捷键","Ctrl / Shift / Alt 与编辑键")[i-1],source="TerminalKeys.kt")
        }
        compose.mainClock.autoAdvance=false
        click("terminal-key-Ctrl");compose.mainClock.advanceTimeBy(500);click("terminal-key-Shift")
        shot("terminal","02-sticky","终端：Ctrl + Shift 粘滞等待",source="TerminalKeys.kt",keepKeyboard=true)
        click("terminal-key-Ctrl");click("terminal-key-Shift")
        click("terminal-editor");type("command-input","请检查这次修改，重点关注 SSH 连接复用和中文输入。\n完成后运行测试，并说明剩余问题。")
        shot("terminal","03-editor","终端：语音/长文本编辑卡片",source="MainActivity.kt")
        click("command-input");shot("terminal","04-editor-keyboard","编辑卡片与软键盘",source="MainActivity.kt",keepKeyboard=true)
        click("close-text-editor")
        click("terminal-keyboard");SystemClock.sleep(800)
        shot("terminal","05-keyboard","终端直接输入：键盘唤起",source="DirectTerminalInput.kt / terminal.js",keepKeyboard=true)
        compose.onNodeWithTag("terminal-tools").performSemanticsAction(SemanticsActions.OnClick) {it()}
        shot("terminal","06-tools-top","终端工具：回复目标与输入",source="MainActivity.kt")
        compose.onNodeWithTag("scroll-speed").performScrollTo();shot("terminal","07-tools-display","终端工具：滚动、字号与复制",source="MainActivity.kt")
        mainTerminal();click("quick-replies");shot("terminal","09-replies","快捷回复列表",source="QuickReplySheet.kt")
        click("add-quick-reply");shot("terminal","10-reply-add","添加快捷回复",source="QuickReplySheet.kt")
        mainTerminal();click("quick-replies");click("edit-quick-reply-0");shot("terminal","11-reply-edit","编辑/删除快捷回复",source="QuickReplySheet.kt")
        mainTerminal();click("terminal-title");shot("terminal","12-details","终端连接详细信息",source="MainActivity.kt")
        for((name,title,message) in listOf(
            Triple("13-connecting","终端：连接准备中", "目标服务器：正在连接…"),
            Triple("14-auth-failed","终端：认证失败", "目标服务器：认证失败"),
            Triple("15-disconnected","终端：断线后的历史与重连", "连接已断开"),
            Triple("16-pane-blocked","终端：回复目标校验拦截", "未发送：当前显示的 pane 与回复目标不同，请切回原目标或显式重新绑定。"))) {
            mainTerminal()
            compose.runOnUiThread {
                if(name!="16-pane-blocked") state("wireToken","")
                state("busy",name=="13-connecting")
                if(name=="13-connecting" || name=="14-auth-failed") state("renderedBytes",0L)
                state("status",TerminalStatus(UiText.Raw(message),name!="13-connecting"))
                // Staged progress card fixture: one jump host, target authenticating or failed.
                val steps=listOf(ConnectStep(uiText(R.string.step_hop_auth,hopText(1)),StepState.DONE,146),
                    ConnectStep(uiText(R.string.step_hop_auth,hopText(0)),if(name=="14-auth-failed") StepState.FAILED else StepState.ACTIVE),
                    ConnectStep(uiText(R.string.step_enter_tmux,"code-review"),StepState.PENDING))
                state("connectSteps",if(name=="13-connecting" || name=="14-auth-failed") steps else emptyList<ConnectStep>())
                state("connectRoute",listOf(hopText(1),hopText(0)))
                state("connectFailure",if(name=="14-auth-failed") ConnectionFailure(0,FailureReason.AUTH,IllegalStateException("fixture")) else null)
            }
            shot("terminal",name,title,"production-ui","MainActivity.kt","真实状态叠层，测试固定对应状态，无网络连接。")
        }
        for(changed in listOf(false,true)) {
            mainTerminal()
            compose.runOnUiThread {state("challenge",HostKeyChallenge("[192.0.2.10]:22","catalog-public-key","SHA256:UiCatalogPublicFixtureOnly",changed))}
            shot("terminal",if(changed) "19-host-changed" else "18-host-first",if(changed) "终端：主机密钥变化拦截" else "终端：首次指纹确认",source="MainActivity.kt")
        }
        dark=false;mainTerminal();shot("terminal","17-light","终端：浅色主题",source="MainActivity.kt");dark=true
        render {TerminalCopyDialog(TerminalSnapshot("Code Review Agent\n\n已完成连接池与输入链路检查。\n✓ SSH 连接可复用\n✓ 测试通过，等待下一条指令",false,0),14,true,{})}
        shot("terminal","08-copy","终端文字静态副本与复制","component-fixture","TerminalCopyDialog.kt","真实复制窗口，传入演示终端文字。")
    }

}

/** Separate rule host keeps MainActivity in the normal test lifecycle, without nested tasks. */
class UiTerminalCatalogTest : UiCatalogTest()
