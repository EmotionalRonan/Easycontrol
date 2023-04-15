package top.saymzx.scrcpy_android

import android.annotation.SuppressLint
import android.content.*
import android.content.Intent.*
import android.content.pm.ActivityInfo
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.MotionEvent.*
import android.view.WindowManager.LayoutParams
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import javax.xml.bind.DatatypeConverter
import kotlin.math.abs


class Configs : ViewModel() {
  // 被控端IP地址
  var remoteIp = ""

  // 被控端ADB端口
  val remotePort = 5555

  // 被控端socket端口
  val remoteSocketPort = 6006

  // 被控端屏幕大小（缩放后的，即视频流大小）
  var remoteWidth = 0
  var remoteHeight = 0

  // 主控端屏幕大小
  var localWidth = 0
  var localHeight = 0

  // 解码器
  lateinit var videoDecodec: MediaCodec
  lateinit var audioDecodec: MediaCodec

  // 状态标识(-1停，0初始，1发送server后，2连接server后，3投屏中，4结束)
  var status = -1

  // 连接流
  lateinit var adbStream: AdbStream
  lateinit var videoStream: DataInputStream
  lateinit var audioStream: DataInputStream
  lateinit var controlStream: DataOutputStream

  // 控制队列
  val controls = LinkedList<ByteArray>() as Queue<ByteArray>

  // 触摸xy记录（为了适配有些设备过于敏感，将点击识别为小范围移动）
  val pointerList = ArrayList<Int>(20)

  // 悬浮窗
  lateinit var floatLayoutParams: LayoutParams

  @SuppressLint("StaticFieldLeak")
  lateinit var surfaceView: SurfaceView

  // 音频播放器
  lateinit var audioTrack: AudioTrack

  // 音频放大器
  lateinit var loudnessEnhancer: LoudnessEnhancer

  // 屏幕广播（熄屏与旋转）
  var screenReceiver: MainActivity.ScreenReceiver? = null

}

class MainActivity : AppCompatActivity() {

  private lateinit var configs: Configs

  // 创建界面
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 推荐主控端使用全面屏手势，以减少冲突，如是旧平板则可使用以下代码，减少三大金刚键的影响
    // 主控端（平板准备）:adb shell settings put global policy_control immersive.full=*
    // 主控端（平板准备）:adb shell wm overscan 0,0,0,-48

    // 初始化
    init()
    if (configs.status == 0) {
      Thread {
        // 发送server
        sendServer()
        // 显示悬浮窗
        this.runOnUiThread { setSurface() }
        // 连接server
        connectServer {
          // 配置视频解码器
          setVideoDecodec()
          // 配置音频解码器
          setAudioDecodec()
          // 开启处理线程
          Thread { decodecInput("video") }.start()
          Thread { decodecInput("audio") }.start()
          Thread { decodecOutput("video") }.start()
          Thread { decodecOutput("audio") }.start()
          Thread { controlOutput() }.start()
          Thread { notOffScreen() }.start()
          // 监控触控操作
          setTouchHandle()
          // 设置被控端熄屏
          setPowerOff()
          // 开始投屏
          configs.status = 3
        }
      }.start()
    }
  }

  // 自动恢复界面
  override fun onPause() {
    super.onPause()
    if (!this::configs.isInitialized || (this::configs.isInitialized && configs.status == 0)) {
      finishAndRemoveTask()
      Runtime.getRuntime().exit(0)
    } else {
      val intent = Intent(this, Page2::class.java)
      intent.addCategory(CATEGORY_LAUNCHER)
      intent.flags =
        FLAG_ACTIVITY_BROUGHT_TO_FRONT or FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
      startActivity(intent)
    }
  }

  // 初始化检测
  private fun init() {
    // 全屏显示
    window.decorView.systemUiVisibility =
      (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    // viewModel
    configs = ViewModelProvider(this)[Configs::class.java]
    // 注册广播用以关闭程序
    val filter = IntentFilter()
    filter.addAction(ACTION_SCREEN_OFF)
    filter.addAction(ACTION_CONFIGURATION_CHANGED)
    configs.screenReceiver = ScreenReceiver()
    registerReceiver(configs.screenReceiver, filter)
    // 读取配置
    val configFile = File(this.applicationContext.filesDir, "configs")
    if (!configFile.isFile) {
      val builder: AlertDialog.Builder = AlertDialog.Builder(this)
      builder.setTitle("请输入被控端IP地址") //设置对话框标题
      val edit = EditText(this)
      builder.setView(edit)
      builder.setPositiveButton(
        "确认"
      ) { _, _ ->
        configFile.writeText(edit.text.toString())
        finish()
        startActivity(intent)
      }
      builder.setCancelable(false)
      val dialog = builder.create()
      dialog.setCanceledOnTouchOutside(false)
      dialog.show()
      return
    }
    configs.remoteIp = configFile.readText().replace("\\s|\\n|\\r|\\t".toRegex(), "")
    // 检查悬浮窗权限
    if (!Settings.canDrawOverlays(this)) {
      Toast.makeText(this, "当前无权限，请授权", Toast.LENGTH_SHORT).show()
      var intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
      intent.data = Uri.parse("package:$packageName")
      startActivity(intent)
      while (!Settings.canDrawOverlays(this)) Thread.sleep(1000)
      intent = Intent(this, this::class.java)
      intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
      this.startActivity(intent)
      Toast.makeText(this, "重新打开软件进行投屏", Toast.LENGTH_SHORT).show()
      finishAndRemoveTask()
      Runtime.getRuntime().exit(0)
    }
    // 获取主控端分辨率
    val metric = DisplayMetrics()
    windowManager.defaultDisplay.getRealMetrics(metric)
    configs.localWidth = metric.widthPixels
    configs.localHeight = metric.heightPixels
    if (configs.status == -1) configs.status = 0
  }

  // 设置悬浮窗
  private fun setSurface() {
    // 创建显示悬浮窗
    configs.surfaceView = SurfaceView(this)

    configs.floatLayoutParams = LayoutParams().apply {
      type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) LayoutParams.TYPE_APPLICATION_OVERLAY
        else LayoutParams.TYPE_SYSTEM_ALERT
      flags =
        LayoutParams.FLAG_LAYOUT_NO_LIMITS or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or LayoutParams.FLAG_KEEP_SCREEN_ON      //位置大小设置
      width = configs.localWidth
      height = configs.localHeight
      gravity = Gravity.START and Gravity.TOP
      x = 0
      y = 0
    }
    // 将悬浮窗控件添加到WindowManager
    windowManager.addView(configs.surfaceView, configs.floatLayoutParams)
  }

  // 视频解码器
  private fun setVideoDecodec() {
    // CodecMeta
    configs.videoStream.readInt()
    configs.remoteWidth = configs.videoStream.readInt()
    configs.remoteHeight = configs.videoStream.readInt()
    // 创建解码器
    configs.videoDecodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val mediaFormat = MediaFormat.createVideoFormat(
      MediaFormat.MIMETYPE_VIDEO_AVC, configs.remoteWidth, configs.remoteHeight
    )
    // 获取视频标识头
    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(readFrame(configs.videoStream)))
    mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(readFrame(configs.videoStream)))
    // 配置解码器
    while (!configs.surfaceView.holder.surface.isValid) Thread.sleep(10)
    configs.videoDecodec.configure(mediaFormat, configs.surfaceView.holder.surface, null, 0)
    // 启动解码器
    configs.videoDecodec.start()
  }

  // 音频解码器
  private fun setAudioDecodec() {
    // CodecMeta
    configs.audioStream.readInt()
    // 创建音频解码器
    configs.audioDecodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val sampleRate = 44100
    val channelCount = 2
    val bitRate = 128000
    val mediaFormat =
      MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount)
    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
    // 获取音频标识头
    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(readFrame(configs.audioStream)))
    // csd1和csd2暂时没用到，所以默认全是用0
    val csd12bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val csd12ByteBuffer = ByteBuffer.wrap(csd12bytes, 0, csd12bytes.size)
    mediaFormat.setByteBuffer("csd-1", csd12ByteBuffer)
    mediaFormat.setByteBuffer("csd-2", csd12ByteBuffer)
    // 配置解码器
    configs.audioDecodec.configure(mediaFormat, null, null, 0)
    // 启动解码器
    configs.audioDecodec.start()
    // 初始化音频播放器
    val minBufferSize = AudioTrack.getMinBufferSize(
      sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
    )
    configs.audioTrack = AudioTrack.Builder().setAudioAttributes(
      AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    ).setAudioFormat(
      AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    ).setBufferSizeInBytes(minBufferSize * 8).build()
    // 声音增强
    try {
      configs.loudnessEnhancer = LoudnessEnhancer(configs.audioTrack.audioSessionId)
      configs.loudnessEnhancer.setTargetGain(4000)
      configs.loudnessEnhancer.enabled = true
    } catch (_: IllegalArgumentException) {
    }
    configs.audioTrack.play()
  }

  // 输入
  private fun decodecInput(mode: String) {
    while (configs.status != 3) Thread.sleep(200)
    var inIndex: Int
    val stream = if (mode == "video") configs.videoStream else configs.audioStream
    val decodec = if (mode == "video") configs.videoDecodec else configs.audioDecodec
    // 开始解码
    while (true) {
      // 向缓冲区输入数据帧
      val buffer = readFrame(stream)
      // 找到一个空的输入缓冲区
      do {
        inIndex = decodec.dequeueInputBuffer(0)
      } while (inIndex < 0)
      decodec.getInputBuffer(inIndex)!!.put(buffer)
      // 提交解码器解码
      decodec.queueInputBuffer(inIndex, 0, buffer.size, 0, 0)
    }
  }

  // 输出
  private fun decodecOutput(mode: String) {
    while (configs.status != 3) Thread.sleep(200)
    var outIndex: Int
    val bufferInfo = BufferInfo()
    if (mode == "video") {
      while (true) {
        // 找到已完成的输出缓冲区
        outIndex = configs.videoDecodec.dequeueOutputBuffer(bufferInfo, 0)
        if (outIndex < 0) {
          Thread.sleep(8)
          continue
        }
        val fomat = configs.videoDecodec.getOutputFormat(outIndex)
        configs.videoDecodec.releaseOutputBuffer(outIndex, true)
        val width = fomat.getInteger("width")
        val height = fomat.getInteger("height")
        // 检测是否旋转
        if (configs.localWidth < configs.localHeight) {
          if (width > height) {
            configs.remoteWidth = width
            configs.remoteHeight = height
            val tmp = configs.localWidth
            configs.localWidth = configs.localHeight
            configs.localHeight = tmp
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
          }
        } else {
          if (width < height) {
            configs.remoteWidth = width
            configs.remoteHeight = height
            val tmp = configs.localWidth
            configs.localWidth = configs.localHeight
            configs.localHeight = tmp
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
          }
        }
      }
    } else {
      while (true) {
        // 找到已完成的输出缓冲区
        outIndex = configs.audioDecodec.dequeueOutputBuffer(bufferInfo, 0)
        if (outIndex < 0) {
          Thread.sleep(8)
          continue
        }
        configs.audioTrack.write(
          configs.audioDecodec.getOutputBuffer(outIndex)!!,
          bufferInfo.size,
          AudioTrack.WRITE_NON_BLOCKING
        )
        configs.audioDecodec.releaseOutputBuffer(outIndex, false)
      }
    }
  }

  // 控制报文输出
  private fun controlOutput() {
    while (configs.status != 3) Thread.sleep(200)
    while (true) {
      if (configs.controls.isNotEmpty()) configs.controlStream.write(configs.controls.poll())
      else Thread.sleep(1)
    }
  }

  // 防止被控端熄屏
  private fun notOffScreen() {
    while (configs.status != 3) Thread.sleep(200)
    while (true) {
      configs.adbStream.write(" dumpsys deviceidle | grep mScreenOn " + '\n')
      while (true) {
        val str = String(configs.adbStream.read())
        if (str.contains("mScreenOn=true")) break
        else if (str.contains("mScreenOn=false")) {
          configs.adbStream.write(" input keyevent 26 " + '\n')
          break
        }
      }
      Thread.sleep(2000)
    }
  }

  // 被控端熄屏
  private fun setPowerOff() {
    val byteBuffer = ByteBuffer.allocate(2)
    byteBuffer.clear()
    byteBuffer.put(10)
    byteBuffer.put(0)
    byteBuffer.flip()
    configs.controls.offer(byteBuffer.array())
  }

  // 触摸事件
  @SuppressLint("ClickableViewAccessibility")
  private fun setTouchHandle() {
    for (i in 1..20) configs.pointerList.add(0)
    configs.surfaceView.setOnTouchListener { _, event ->
      // 获取本次动作的手指ID以及xy坐标
      val i = event.actionIndex
      val p = event.getPointerId(i)
      val x = event.getX(i).toInt()
      val y = event.getY(i).toInt()

      when (val action = event.action) {
        ACTION_DOWN -> {
          packControl(
            action,
            p,
            x * configs.remoteWidth / configs.localWidth,
            y * configs.remoteHeight / configs.localHeight
          )
          // 记录按下xy信息
          configs.pointerList[p] = x
          configs.pointerList[p + 10] = y
        }
        ACTION_UP -> {
          packControl(
            action,
            p,
            x * configs.remoteWidth / configs.localWidth,
            y * configs.remoteHeight / configs.localHeight
          )
        }
        ACTION_MOVE -> {
          // 适配一些机器将点击视作小范围移动
          if ((abs(configs.pointerList[p] - x) > 3 && abs(configs.pointerList[p + 10] - y) > 3)) packControl(
            action,
            p,
            x * configs.remoteWidth / configs.localWidth,
            y * configs.remoteHeight / configs.localHeight
          )
        }
      }
      return@setOnTouchListener true
    }
  }

  // 组装触控报文
  private fun packControl(action: Int, p: Int, x: Int, y: Int) {
    val byteBuffer = ByteBuffer.allocate(32)
    byteBuffer.clear()
    // 触摸事件
    byteBuffer.put(2)
    // 触摸类型
    byteBuffer.put(action.toByte())
    // pointerId
    byteBuffer.putLong(p.toLong())
    // 坐标位置
    byteBuffer.putInt(x)
    byteBuffer.putInt(y)
    // 屏幕尺寸
    byteBuffer.putShort(configs.remoteWidth.toShort())
    byteBuffer.putShort(configs.remoteHeight.toShort())
    // 按压力度presureInt和buttons
    byteBuffer.putShort(0)
    byteBuffer.putInt(0)
    byteBuffer.putInt(0)
    byteBuffer.flip()
    configs.controls.offer(byteBuffer.array())
  }

  // 发送server
  private fun sendServer() {
    // 读取scrcpy server
    val assetManager = assets
    val serverFileBase64: ByteArray?
    val inputStream = assetManager.open("scrcpy-server.jar")
    val buffer = ByteArray(inputStream.available())
    inputStream.read(buffer)
    serverFileBase64 = Base64.encode(buffer, 2)
    // 读取或创建保存配对密钥
    val public = File(this.applicationContext.filesDir, "public.key")
    val private = File(this.applicationContext.filesDir, "private.key")
    val crypto: AdbCrypto
    if (public.isFile && private.isFile) {
      crypto = AdbCrypto.loadAdbKeyPair({ data: ByteArray? ->
        DatatypeConverter.printBase64Binary(data)
      }, private, public)
    } else {
      crypto = AdbCrypto.generateAdbKeyPair { data -> DatatypeConverter.printBase64Binary(data) }
      crypto.saveAdbKeyPair(private, public)
    }
    // 连接ADB
    val socket = Socket()
    try {
      socket.connect(InetSocketAddress(configs.remoteIp, configs.remotePort), 1000)
    } catch (_: IOException) {
      runOnUiThread { Toast.makeText(this, "连接失败，请检查IP地址以及是否开启ADB网络调试", Toast.LENGTH_SHORT).show() }
      Thread.sleep(1000)
      finishAndRemoveTask()
      Runtime.getRuntime().exit(0)
    }
    val connection = AdbConnection.create(socket, crypto)
    connection.connect()
    // 发送文件
    configs.adbStream = connection.open("shell:")
    configs.adbStream.write(" cd /data/local/tmp " + '\n')
    configs.adbStream.write(" rm serverBase64 " + '\n')
    configs.adbStream.write(" ps -ef | grep scrcpy | grep -v grep | awk '{print $2}' | xargs kill -9" + '\n')
    var serverBase64part: String
    val len: Int = serverFileBase64.size
    var sourceOffset = 0
    while (sourceOffset < len) {
      if (len - sourceOffset >= 4056) {
        val filePart = ByteArray(4056)
        System.arraycopy(serverFileBase64!!, sourceOffset, filePart, 0, 4056)
        sourceOffset += 4056
        serverBase64part = String(filePart, StandardCharsets.US_ASCII)
      } else {
        val rem = len - sourceOffset
        val remPart = ByteArray(rem)
        System.arraycopy(serverFileBase64!!, sourceOffset, remPart, 0, rem)
        sourceOffset += rem
        serverBase64part = String(remPart, StandardCharsets.US_ASCII)
      }
      configs.adbStream.write(" echo $serverBase64part >> serverBase64\n")
    }
    configs.adbStream.write(" base64 -d < serverBase64 > scrcpy-server.jar && rm serverBase64" + '\n')
    Thread.sleep(100)
    // 修改分辨率
    configs.adbStream.write(" wm size " + configs.localWidth + "x" + configs.localHeight + '\n')
    configs.adbStream.write(" cmd overlay disable com.android.internal.systemui.navbar.gestural " + '\n')
    configs.adbStream.write(" cmd overlay enable com.android.internal.systemui.navbar.threebutton " + '\n')
    // 启动scrcpy server
    configs.adbStream.write(" CLASSPATH=./scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 2.0 > /dev/null 2>&1 & " + '\n')
    // 修改状态
    Log.d("scrcpy", "发送server完成")
    configs.status = 1
  }

  // 连接server
  private fun connectServer(callback: () -> Unit) {
    var videoSocket: Socket? = null
    var audioSocket: Socket? = null
    var controlSocket: Socket? = null
    // 避免server未启动完成
    for (i in 1..20) {
      try {
        if (videoSocket == null) videoSocket = Socket(configs.remoteIp, configs.remoteSocketPort)
        if (audioSocket == null) audioSocket = Socket(configs.remoteIp, configs.remoteSocketPort)
        controlSocket = Socket(configs.remoteIp, configs.remoteSocketPort)
        break
      } catch (_: IOException) {
        Log.e("scrcpy", "连接server失败，再次尝试")
        Thread.sleep(500)
      }
    }
    configs.videoStream = DataInputStream(videoSocket?.getInputStream())
    configs.audioStream = DataInputStream(audioSocket?.getInputStream())
    configs.controlStream = DataOutputStream(controlSocket?.getOutputStream())
    // 修改状态
    Log.d("scrcpy", "连接server完成")
    configs.status = 2
    callback()
  }

  // 从socket流中解析数据
  private fun readFrame(stream: DataInputStream): ByteArray {
    stream.readLong()
    val size = stream.readInt()
    val buffer = ByteArray(size)
    stream.readFully(buffer, 0, size)
    return buffer
  }

  // 按键广播处理
  inner class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
      when (p1?.action) {
        ACTION_CONFIGURATION_CHANGED -> {
          configs.floatLayoutParams.apply {
            width = configs.localWidth
            height = configs.localHeight
          }
          windowManager.updateViewLayout(configs.surfaceView, configs.floatLayoutParams)
        }
        ACTION_SCREEN_OFF -> {
          // 恢复被控端屏幕
          if (configs.status > 0) {
            Thread {
              configs.adbStream.write(" wm size reset " + '\n')
              configs.adbStream.write(" cmd overlay disable com.android.internal.systemui.navbar.threebutton " + '\n')
              configs.adbStream.write(" cmd overlay enable com.android.internal.systemui.navbar.gestural " + '\n')
              configs.status = 4
            }.start()
            while (configs.status != 4) Thread.sleep(50)
          }
          finishAndRemoveTask()
          Runtime.getRuntime().exit(0)
        }
      }
    }
  }

}