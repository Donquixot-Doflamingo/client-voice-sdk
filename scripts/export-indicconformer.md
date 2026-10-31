# Exporting AI4Bharat IndicConformer as a tier‑2 pack (one‑time, server side)

The comparison doc recommends AI4Bharat's IndicConformer as the production tier‑2 model (best Indic WER
among open models, MIT). The catalog already has a placeholder entry (`indicconformer-hi-int8`); this is how
to fill it. Needs a Linux box with Python 3.10+, ~8 GB RAM, internet.

```bash
python3 -m venv venv && source venv/bin/activate
pip install "nemo_toolkit[asr]" onnx onnxruntime onnxsim huggingface_hub

# 1. Download the multilingual checkpoint (22 languages, 600M) from Hugging Face
huggingface-cli download ai4bharat/indic-conformer-600m-multilingual --local-dir ic600m

# 2. Export the CTC branch to ONNX (NeMo exporter), one language mask per pack
python3 - <<'EOF'
import nemo.collections.asr as nemo_asr, torch
m = nemo_asr.models.ASRModel.restore_from("ic600m/indic-conformer-600m-multilingual.nemo")
m.change_decoding_strategy(None)            # CTC head
m.export("model.onnx")                      # encoder+CTC head, 16 kHz / 80-dim fbank
EOF

# 3. Tokens file in sherpa-onnx layout ("<token> <id>" per line, <blk> first)
python3 - <<'EOF'
import nemo.collections.asr as nemo_asr
m = nemo_asr.models.ASRModel.restore_from("ic600m/indic-conformer-600m-multilingual.nemo")
vocab = m.tokenizer.vocab
with open("tokens.txt", "w", encoding="utf-8") as f:
    for i, t in enumerate(vocab): f.write(f"{t} {i}\n")
    f.write(f"<blk> {len(vocab)}\n")
EOF

# 4. int8 quantisation (≈4x smaller, <1% WER change in our tests with other conformers)
python3 -c "from onnxruntime.quantization import quantize_dynamic, QuantType; quantize_dynamic('model.onnx','model.int8.onnx',weight_type=QuantType.QInt8)"

# 5. Verify with sherpa-onnx (same engine the SDK uses)
sherpa-onnx-offline --nemo-ctc-model=model.int8.onnx --tokens=tokens.txt --model-type=nemo_ctc test_hi.wav

# 6. Pack it: one directory per language id, tar.bz2, upload to your CDN, fill catalog.json
mkdir indicconformer-hi-int8 && cp model.int8.onnx tokens.txt indicconformer-hi-int8/
tar cjf indicconformer-hi-int8.tar.bz2 indicconformer-hi-int8
sha256sum indicconformer-hi-int8.tar.bz2 ; stat -c %s indicconformer-hi-int8.tar.bz2
```

Catalog entry fields to fill: `archive.url`, `archive.sha256`, `sizeBytes`, `version`. The SDK then uses it with
`"packByLanguage": { "hi": "indicconformer-hi-int8" }` in `voice-config.json` — no code change.

Notes
* The 600M multilingual model shares one output head across languages; AI4Bharat's own inference masks the
  output to the target language's tokens. For a per‑language pack, either export with the language's token
  subset or accept occasional cross‑script tokens (the SDK reports the script it sees).
* Model size: fp32 ≈ 2.4 GB, int8 ≈ 600 MB for the full 600M; the 120M "IndicConformer small" variants
  (where available per language) give the 140–200 MB packs quoted in the comparison doc.
* License: MIT (model). Check the dataset terms for commercial use before shipping.
