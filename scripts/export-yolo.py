"""Export a locally supplied COCO checkpoint; use the matching pinned export environment.

Usage: TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD=1 python scripts/export-yolo.py /path/to/yolov8n.pt --size 416
Only load a checkpoint you trust: PyTorch .pt files may contain executable pickle data.
"""
import os
os.environ.setdefault("YOLO_CONFIG_DIR", "/tmp/helios-yolo-settings")
import argparse
import shutil
from pathlib import Path
import torch
import onnx
from ultralytics import YOLO

parser = argparse.ArgumentParser()
parser.add_argument("checkpoint", type=Path)
parser.add_argument("--size", type=int, default=320, choices=(320, 416, 640))
args = parser.parse_args()
original = torch.onnx.export
def legacy_export(*a, **kw):
    kw["dynamo"] = False
    return original(*a, **kw)
torch.onnx.export = legacy_export
model = YOLO(str(args.checkpoint))
assert len(model.names) == 80 and model.names[0] == "person"
options = dict(format="onnx", imgsz=args.size, opset=17, simplify=False, dynamic=False)
if args.checkpoint.stem.startswith("yolo26"):
    # Match the existing raw xywh + class-score Android decoder in Ultralytics 8.4.14.
    options.update(end2end=False, nms=False)
output = model.export(**options)
onnx.checker.check_model(onnx.load(output))
target = Path(__file__).resolve().parents[1] / f"app/src/main/assets/yolo/{args.checkpoint.stem}-{args.size}.onnx"
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(output, target)
print(target)
