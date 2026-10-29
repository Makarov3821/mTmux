本地 X-ASR 迁移验证。

- builds.json：本地构建、包体、哈希、JVM 和 Audio Lab 回归检查。
- python-tests.txt：固定资源下载/安装测试。
- desktop-smoke.json：公开固定中英文音频的桌面模型兼容性检查，不是手机性能或准确率。
- android-4threads-interrupted.txt：软件 TCG 模拟器，前四项（取消前、升级清理、取消进行中、录音释放）通过。中文实际解码等待数分钟后，由测试驱动主动执行 am force-stop 终止，末尾 Process crashed 是此次主动终止的结果；该轮未完成，不能计为全通过。

- android-1thread-interrupted.txt：相同软件 TCG 模拟器，asrThreads=1。前四项再次通过；中文实际解码也在停止前完成，断言通过，耗时 222,749ms（只代表软件 TCG，非手机性能）。输出“欢迎大家来体验达摩院推出的语音识别模型”。英文用例启动后由驱动主动终止，未取得英文端侧结果。末尾 Process crashed 由主动 force-stop 造成。本轮不计为完整通过。

生产默认最多 4 线程；测试参数 asrThreads 只影响测试调用。专用模拟器 emulator-5580 已关闭，未留下后台推理。

结论：Android 模型安装、中文完整解码、请求取消、升级清理与录音资源链路已覆盖；英文端侧转写及 ARM64 真机未验证完成。桌面端固定中文和英文解码均完成。不能用此结果报告手机准确率或时延。
