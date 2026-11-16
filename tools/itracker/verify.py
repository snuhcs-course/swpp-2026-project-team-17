"""CPU smoke test of the exact packaged model; does not measure human gaze accuracy."""
import json
import time
from pathlib import Path
import cv2
import numpy as np

root = Path(__file__).resolve().parents[2]
p = root / "app/src/main/assets/itracker"
cv2.setNumThreads(2)
net = cv2.dnn.readNetFromCaffe(str(p/"deploy.prototxt"), str(p/"weights.caffemodel"))
images = {"image_"+name: np.zeros((1,3,224,224), np.float32) for name in ("left","right","face")}
images["facegrid"] = np.zeros((1,625,1,1), np.float32)


def run(inputs):
    for name, data in inputs.items():
        net.setInput(data,name)
    start = time.perf_counter()
    output = net.forward("fc3").copy()
    elapsed = (time.perf_counter()-start)*1000
    assert output.shape == (1,2) and np.isfinite(output).all()
    return output,elapsed


baseline,_ = run(images)
np.testing.assert_allclose(baseline,[[1.270289,-4.482550]],atol=.01)
effects = {}
for name in images:
    altered = {k:v.copy() for k,v in images.items()}
    altered[name].fill(1 if name=="facegrid" else 32)
    out,ms = run(altered)
    change = float(np.linalg.norm(out-baseline))
    assert change > 1e-5, f"Disconnected model input: {name}"
    effects[name] = {"output_cm":out.tolist(), "change":change,"desktop_ms":ms}
for name in ("left","right","face"):
    means=np.fromfile(p/f"mean_{name}.f32",dtype="<f4").reshape(1,3,224,224)
    assert np.isfinite(means).all()
    images["image_"+name] = 128-means
out,ms=run(images)
report={"opencv":cv2.__version__,"zero_input_cm":baseline.tolist(),"input_sensitivity":effects,
        "mean_subtracted_fixture_cm":out.tolist(),"desktop_ms":ms,
        "scope":"Model execution and tensor wiring only; no S23 latency or gaze-accuracy claim."}
dest=root/"output/itracker-validation";dest.mkdir(parents=True,exist_ok=True)
(dest/"smoke.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
print(json.dumps(report,indent=2))
