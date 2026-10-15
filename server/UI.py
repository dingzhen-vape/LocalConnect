"""
LocalConnect 电脑端界面

职责：Ditto 风格的缩略图网格——把缩略图平铺出来、能被选中。
不碰网络：缩略图从哪来由调用方决定（见 app.py）。

缩略图数据格式：dict[str, bytes]，键是图片 id，值是 JPEG 字节。
"""

from loguru import logger
from PyQt6.QtCore import QSize, Qt
from PyQt6.QtGui import QIcon, QPixmap
from PyQt6.QtWidgets import (
    QListWidget,
    QListWidgetItem,
    QMainWindow,
)

# 模块级常量：全大写下划线

# 每个格子的显示尺寸（像素）。图片会等比缩放到「能放进去」为止，
# 不足的部分留白——绝不裁剪，也不改变比例。
CELL_SIZE = 160


class MainWindow(QMainWindow):
    """普通窗口：系统标题栏 + 缩略图网格。

    每个缩略图是一个可【选中】的格子（不是点击触发动作的按钮）。
    选中的项可用 selectedThumbId() 取到，供阶段二"请求原图"使用。

    窗口装饰全部交给系统：最小化 / 最大化 / 关闭都由标题栏提供，
    拖动和缩放也由系统处理，不用自己实现。
    """

    def __init__(self):
        super().__init__()
        self.setWindowTitle("LocalConnect")
        self.resize(720, 560)

        # 当前显示的缩略图数量
        self._count = 0

        # 已显示过的 id。同一张重复发来时跳过，否则界面会堆满重复的图。
        self._shown: set[str] = set()

        self.buildUI()

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

        # Ditto 风格：深色背景 + 格子之间留细缝做分隔
        self.listWidget.setStyleSheet(
            """
            QListWidget {
                border: none;
                outline: none;
                background: #2b2b2b;
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
        item.setToolTip(f"id: {thumb_id}")                        # 悬浮显示 id
        item.setSizeHint(QSize(CELL_SIZE, CELL_SIZE))
        item.setData(Qt.ItemDataRole.UserRole, thumb_id)          # 存 id，供后续取用
        self.listWidget.addItem(item)

        self._shown.add(thumb_id)
        self._count += 1

    @property
    def thumbnailCount(self) -> int:
        """当前已显示的缩略图数量。"""
        return self._count

    def selectedThumbId(self) -> str | None:
        """当前选中项的 id；没有选中则返回 None。

        阶段二「按 id 请求原图」会用这个方法取到要请求的 id。
        """
        items = self.listWidget.selectedItems()
        if not items:
            return None
        return items[0].data(Qt.ItemDataRole.UserRole)
