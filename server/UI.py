"""
LocalConnect 电脑端界面

职责：Ditto 风格的缩略图网格——把缩略图平铺出来、能被选中。
不碰网络：缩略图从哪来由调用方决定（见 app.py）。

缩略图数据格式：dict[str, bytes]，键是图片 id，值是 JPEG 字节。
"""

import ctypes
import sys

from loguru import logger
from PyQt6.QtCore import QEvent, QSize, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QColor, QIcon, QPalette, QPixmap
from PyQt6.QtWidgets import (
    QListWidget,
    QListWidgetItem,
    QMainWindow,
)

# 模块级常量：全大写下划线

# 每个格子的显示尺寸（像素）。图片会等比缩放到「能放进去」为止，
# 不足的部分留白——绝不裁剪，也不改变比例。
CELL_SIZE = 160


# ============================================================================
#  Windows 毛玻璃（DWM Acrylic）
# ============================================================================


class MARGINS(ctypes.Structure):
    _fields_ = [
        ("cxLeftWidth", ctypes.c_int),
        ("cxRightWidth", ctypes.c_int),
        ("cyTopHeight", ctypes.c_int),
        ("cyBottomHeight", ctypes.c_int),
    ]


def extendFrameIntoClientArea(hwnd: int):
    """把窗口框架的玻璃区域延伸到整个客户区。

    DWM 的材质默认只画在标题栏，客户区是 Qt 自己画的实心底色。
    传 -1 表示「整宽/整高」，玻璃区就铺满整个窗口了。
    """
    m = MARGINS()
    m.cxLeftWidth = m.cxRightWidth = -1
    m.cyTopHeight = m.cyBottomHeight = -1
    ctypes.windll.dwmapi.DwmExtendFrameIntoClientArea(hwnd, ctypes.byref(m))


def setupAcrylic(hwnd: int):
    """给窗口应用 Acrylic 毛玻璃，并把标题栏切成深色。

    只有 Windows 11 22H2 以上支持 SYSTEMBACKDROP_TYPE。
    其他系统上静默跳过——调用失败不影响程序运行，只是没有毛玻璃。
    """
    dwm = ctypes.windll.dwmapi

    # 标题栏深色：不设的话标题栏是浅色，和深色界面凑在一起显得割裂
    dark = ctypes.c_int(1)
    dwm.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(dark), ctypes.sizeof(dark))

    # 背景材质 = Acrylic（3）
    backdrop = ctypes.c_int(3)
    ok = dwm.DwmSetWindowAttribute(
        hwnd, 38, ctypes.byref(backdrop), ctypes.sizeof(backdrop)
    )
    extendFrameIntoClientArea(hwnd)

    if ok == 0:
        logger.info("毛玻璃背景已启用（Acrylic）")
    else:
        logger.warning("当前系统不支持 Acrylic 背景，已降级为纯色")


class MainWindow(QMainWindow):
    """普通窗口：系统标题栏 + 缩略图网格。

    每个缩略图是一个可【选中】的格子（不是点击触发动作的按钮）。
    选中的项可用 selectedThumbId() 取到，供阶段二"请求原图"使用。

    窗口装饰全部交给系统：最小化 / 最大化 / 关闭都由标题栏提供，
    拖动和缩放也由系统处理，不用自己实现。
    """

    fetchRequested = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.setWindowTitle("LocalConnect")
        self.resize(720, 560)

        # 已显示过的 id。同一张重复发来时跳过，否则界面会堆满重复的图。
        self._shown: set[str] = set()

        # 让窗口背景真正透明，DWM 的材质才能透出来。
        # 必须在 show() 之前设置，否则不生效。
        # 代价：窗口的"不透明区域"由我们自己决定，所以子控件也必须透明。
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)

        self.buildUI()

        # 等窗口真正显示后再应用毛玻璃 —— 那时 winId() 才是有效句柄
        QTimer.singleShot(0, self.applyAcrylic)

    def applyAcrylic(self):
        """窗口显示后应用毛玻璃。非 Windows 系统直接跳过。"""
        if sys.platform != "win32":
            return
        try:
            setupAcrylic(int(self.winId()))
        except Exception as e:
            logger.warning(f"毛玻璃设置失败（不影响使用）: {e}")

    # ------------------------------------------------------------------
    #  界面搭建
    # ------------------------------------------------------------------

    def buildUI(self):
        """搭出窗口：一个缩略图网格。"""

        self.listWidget = QListWidget()

        # 网格 + 图标模式：每个条目一个等大的格子
        self.listWidget.setViewMode(QListWidget.ViewMode.IconMode)
        self.listWidget.setIconSize(QSize(CELL_SIZE, CELL_SIZE))
        self.listWidget.setGridSize(QSize(CELL_SIZE, CELL_SIZE))
        self.listWidget.setResizeMode(QListWidget.ResizeMode.Adjust)
        self.listWidget.setMovement(QListWidget.Movement.Static)

        # 单选：一次只选一张图
        self.listWidget.setSelectionMode(QListWidget.SelectionMode.SingleSelection)

        # 零边距、零间距
        self.listWidget.setSpacing(0)
        self.listWidget.setContentsMargins(0, 0, 0, 0)
        self.listWidget.setUniformItemSizes(True)
        self.listWidget.installEventFilter(self)
        # Ditto 风格：深色背景 + 格子之间留细缝做分隔
        self.listWidget.setStyleSheet(
            """
            QListWidget {
                border: none;
                outline: none;
                background: transparent;
                padding: 0px;
            }
            QListWidget::item {
                border: 1px solid #3c3c3c;
                margin: 0px;
                padding: 0px;
            }
            QListWidget::item:selected {
                border: 1px solid #4a9eff;
            }
            """
        )

        self.setCentralWidget(self.listWidget)

        # 各层都透明，否则会盖住 DWM 的毛玻璃材质
        for widget in (self, self.listWidget):
            widget.setAutoFillBackground(False)
            palette = widget.palette()
            palette.setColor(QPalette.ColorRole.Window, QColor(0, 0, 0, 0))
            palette.setColor(QPalette.ColorRole.Base, QColor(0, 0, 0, 0))
            widget.setPalette(palette)

    # ------------------------------------------------------------------
    #  对外接口：由 app.py 调用
    # ------------------------------------------------------------------

    def addThumbnail(self, thumb_id: str, data: bytes):
        """追加一张缩略图。

        data 是 JPEG 字节，直接交给 QPixmap 解码。
        解码失败或重复 id 都静默跳过——显示层不该因为一张坏图打断整批。
        """
        if thumb_id in self._shown:
            return

        pixmap = QPixmap()
        if not pixmap.loadFromData(data):
            logger.warning(f"缩略图 {thumb_id} 解码失败（{len(data)} 字节）")
            return

        # KeepAspectRatio：等比缩放到「能放进格子」为止。
        # 不裁剪、不拉伸，比例不足的部分自然留白。
        pixmap = pixmap.scaled(
            CELL_SIZE,
            CELL_SIZE,
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )

        item = QListWidgetItem(QIcon(pixmap), "")
        item.setToolTip(f"id: {thumb_id}")  # 悬浮显示 id
        item.setSizeHint(QSize(CELL_SIZE, CELL_SIZE))
        item.setData(Qt.ItemDataRole.UserRole, thumb_id)  # 存 id，供后续取用
        self.listWidget.addItem(item)

        self._shown.add(thumb_id)

    def clearThumbnails(self):
        """清空网格，让新一批缩略图从零开始铺。

        _shown 必须跟着一起清：只 clear() 列表的话，新批次里 id 相同的图
        会被 addThumbnail 当成「已显示」直接跳过，界面上就会缺图。
        """
        self.listWidget.clear()
        self._shown.clear()

    def selectedThumbId(self) -> str | None:
        """当前选中项的 id；没有选中则返回 None。

        阶段二「按 id 请求原图」会用这个方法取到要请求的 id。
        """
        items = self.listWidget.selectedItems()
        if not items:
            return None
        return items[0].data(Qt.ItemDataRole.UserRole)

    def fetchSelectedOriginal(self):
        """按选中的 id 请求原图。"""
        thumb_id = self.selectedThumbId()
        if thumb_id is None:
            logger.info("没有选中任何缩略图，Ctrl+C 忽略")
            return
        logger.info(f"请求原图: {thumb_id}")
        self.fetchRequested.emit(thumb_id)

    def eventFilter(self, obj, event):
        """拦下 listWidget 上的 Ctrl+C。"""
        if obj is self.listWidget and event.type() == QEvent.Type.KeyPress:  # noqa: SIM102
            if event.key() == Qt.Key.Key_C and (
                event.modifiers() & Qt.KeyboardModifier.ControlModifier
            ):
                self.fetchSelectedOriginal()
                return True  # ★ 必须 True，否则 listWidget 会再处理一遍

        return super().eventFilter(obj, event)  # 其余事件全部交还父类
