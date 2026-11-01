#!/usr/bin/env python3
"""
Kronos -> ONNX exporter for the Android app (one-time step; the GitHub workflow runs it for you).

Produces kronos-onnx.zip with 4 ONNX graphs + manifest.json (sha256 of every file):
  encoder.onnx  x[B,T,6] -> s1[B,T], s2[B,T]                 (tokenizer.encode(half=True))
  s1.onnx       s1_ids, s2_ids, stamp[B,T,5] -> s1_logits[B,V1] (last step), context[B,T,D]
  s2.onnx       context[B,T,D], s1_id[B,1] -> s2_logits[B,V2]   (last step)
  decoder.onnx  s1[B,T], s2[B,T] -> recon[B,T,6]              (tokenizer.decode(half=True))

The script VERIFIES every graph against PyTorch (two different sequence lengths, to prove the
dynamic axes work) and exits with an error if anything differs, so a bad export can never ship.
"""
import argparse, hashlib, json, os, sys, zipfile
import numpy as np
import torch, torch.nn as nn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Kronos, KronosTokenizer
import model.module as M

PRESETS = {
    "mini":  ("NeoQuasar/Kronos-mini",  "NeoQuasar/Kronos-Tokenizer-2k",   2048),
    "small": ("NeoQuasar/Kronos-small", "NeoQuasar/Kronos-Tokenizer-base",  512),
    "base":  ("NeoQuasar/Kronos-base",  "NeoQuasar/Kronos-Tokenizer-base",  512),
}

# --- export-friendly RoPE: the original caches cos/sin keyed on a Python int, which the ONNX
# tracer would freeze to one sequence length. Same maths, no cache. cos/sin come from q's length
# and are applied to both q and k (exactly what the original does, incl. the 1-token cross-attn).
def _rope_forward(self, q, k):
    n = q.shape[-2]
    t = torch.arange(n, device=q.device).type_as(self.inv_freq)
    freqs = torch.einsum("i,j->ij", t, self.inv_freq)
    emb = torch.cat((freqs, freqs), dim=-1)
    cos, sin = emb.cos()[None, None, :, :], emb.sin()[None, None, :, :]
    return (q * cos) + (self._rotate_half(q) * sin), (k * cos) + (self._rotate_half(k) * sin)
M.RotaryPositionalEmbedding.forward = _rope_forward

# --- export-friendly attention. The original calls F.scaled_dot_product_attention(is_causal=True); the
# TorchScript ONNX exporter's lowering of that op is fragile (mask / scale handling differ between torch
# versions) and produced logits that differ from PyTorch. Plain matmul+softmax with an explicit causal
# mask is mathematically identical and exports/validates reliably. Dropout is 0 in eval, so it's omitted.
def _attend(q, k, v, causal):
    scores = torch.matmul(q, k.transpose(-1, -2)) * (q.shape[-1] ** -0.5)
    if causal:
        L, S = q.shape[-2], k.shape[-2]
        i = torch.arange(L, device=q.device).unsqueeze(1)
        j = torch.arange(S, device=q.device).unsqueeze(0)
        neg = torch.full((), torch.finfo(scores.dtype).min, dtype=scores.dtype, device=q.device)
        scores = torch.where(j > i, neg, scores)
    return torch.matmul(torch.softmax(scores, dim=-1), v)

def _self_attn_forward(self, x, key_padding_mask=None):
    b, n, _ = x.shape
    q = self.q_proj(x).view(b, n, self.n_heads, self.head_dim).transpose(1, 2)
    k = self.k_proj(x).view(b, n, self.n_heads, self.head_dim).transpose(1, 2)
    v = self.v_proj(x).view(b, n, self.n_heads, self.head_dim).transpose(1, 2)
    q, k = self.rotary(q, k)
    o = _attend(q, k, v, causal=True).transpose(1, 2).reshape(b, n, self.d_model)
    return self.resid_dropout(self.out_proj(o))

def _cross_attn_forward(self, query, key, value, key_padding_mask=None):
    b, ql, _ = query.shape
    sl = key.shape[1]
    q = self.q_proj(query).view(b, ql, self.n_heads, self.head_dim).transpose(1, 2)
    k = self.k_proj(key).view(b, sl, self.n_heads, self.head_dim).transpose(1, 2)
    v = self.v_proj(value).view(b, sl, self.n_heads, self.head_dim).transpose(1, 2)
    q, k = self.rotary(q, k)
    o = _attend(q, k, v, causal=False).transpose(1, 2).reshape(b, ql, self.d_model)  # eval: is_causal=False
    return self.resid_dropout(self.out_proj(o))

M.MultiHeadAttentionWithRoPE.forward = _self_attn_forward
M.MultiHeadCrossAttentionWithRoPE.forward = _cross_attn_forward


class Enc(nn.Module):
    def __init__(s, tok): super().__init__(); s.tok = tok
    def forward(s, x):
        a, b = s.tok.encode(x, half=True); return a, b
class S1(nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, s1_ids, s2_ids, stamp):
        lg, ctx = s.m.decode_s1(s1_ids, s2_ids, stamp); return lg[:, -1, :], ctx
class S2(nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, context, s1_id):
        return s.m.decode_s2(context, s1_id)[:, -1, :]
class Dec(nn.Module):
    def __init__(s, tok): super().__init__(); s.tok = tok
    def forward(s, a, b): return s.tok.decode([a, b], half=True)


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""): h.update(c)
    return h.hexdigest()

def export(mod, args, names_in, names_out, dyn, path):
    torch.onnx.export(mod, args, path, input_names=names_in, output_names=names_out,
                      dynamic_axes=dyn, opset_version=18, do_constant_folding=True, dynamo=False)

def run_ort(path, feeds):
    import onnxruntime as ort
    return ort.InferenceSession(path, providers=["CPUExecutionProvider"]).run(None, feeds)

def check(name, a, b, tol=2e-3):
    d = float(np.max(np.abs(a - b)))
    scale = max(1.0, float(np.max(np.abs(a))))          # relative to the magnitude of the outputs
    print(f"  [{name}] max|torch-onnx| = {d:.2e}  (rel {d/scale:.2e}, |out|max {scale:.2f})")
    if d / scale > tol: sys.exit(f"VERIFY FAILED for {name}: rel {d/scale} > {tol} (abs {d})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preset", default="mini", choices=PRESETS)
    ap.add_argument("--out", default="out")
    a = ap.parse_args()
    mid, tid, max_ctx = PRESETS[a.preset]
    os.makedirs(a.out, exist_ok=True)
    print(f"Loading {mid} + {tid} ..."); torch.manual_seed(0)
    model = Kronos.from_pretrained(mid).eval(); tok = KronosTokenizer.from_pretrained(tid).eval()
    V1, V2, D = 2 ** model.s1_bits, 2 ** model.s2_bits, model.d_model
    P = lambda n: os.path.join(a.out, n)

    B, T = 2, 48
    x = torch.randn(B, T, 6).clamp(-5, 5)
    s1i = torch.randint(0, V1, (B, T)); s2i = torch.randint(0, V2, (B, T))
    stamp = torch.stack([torch.randint(0, 60, (B, T)), torch.randint(0, 24, (B, T)), torch.randint(0, 7, (B, T)),
                         torch.randint(1, 32, (B, T)), torch.randint(1, 13, (B, T))], -1).float()
    ctx0 = torch.randn(B, T, D); one = torch.randint(0, V1, (B, 1))

    print("Exporting ...")
    export(Enc(tok), (x,), ["x"], ["s1", "s2"], {"x": {0: "b", 1: "t"}, "s1": {0: "b", 1: "t"}, "s2": {0: "b", 1: "t"}}, P("encoder.onnx"))
    export(S1(model), (s1i, s2i, stamp), ["s1_ids", "s2_ids", "stamp"], ["s1_logits", "context"],
           {"s1_ids": {0: "b", 1: "t"}, "s2_ids": {0: "b", 1: "t"}, "stamp": {0: "b", 1: "t"}, "s1_logits": {0: "b"}, "context": {0: "b", 1: "t"}}, P("s1.onnx"))
    export(S2(model), (ctx0, one), ["context", "s1_id"], ["s2_logits"],
           {"context": {0: "b", 1: "t"}, "s1_id": {0: "b"}, "s2_logits": {0: "b"}}, P("s2.onnx"))
    export(Dec(tok), (s1i, s2i), ["s1", "s2"], ["recon"],
           {"s1": {0: "b", 1: "t"}, "s2": {0: "b", 1: "t"}, "recon": {0: "b", 1: "t"}}, P("decoder.onnx"))

    print("Verifying against PyTorch (T=48 and T=200, batch 2 and 3) ...")
    with torch.no_grad():
        for (b, t) in [(2, 48), (3, 200)]:
            x = torch.randn(b, t, 6).clamp(-5, 5)
            s1i = torch.randint(0, V1, (b, t)); s2i = torch.randint(0, V2, (b, t))
            stamp = torch.stack([torch.randint(0, 60, (b, t)), torch.randint(0, 24, (b, t)), torch.randint(0, 7, (b, t)),
                                 torch.randint(1, 32, (b, t)), torch.randint(1, 13, (b, t))], -1).float()
            e1, e2 = Enc(tok)(x); o1, o2 = run_ort(P("encoder.onnx"), {"x": x.numpy()})
            for n, p, q in (("encoder.s1", e1, o1), ("encoder.s2", e2, o2)):
                mism = float((p.numpy() != q).mean()); print(f"  [{n}] token mismatch = {mism:.4f}")
                if mism > 0.01: sys.exit(f"VERIFY FAILED for {n}")
            lg, ctx = S1(model)(s1i, s2i, stamp)
            ol, oc = run_ort(P("s1.onnx"), {"s1_ids": s1i.numpy(), "s2_ids": s2i.numpy(), "stamp": stamp.numpy()})
            check("s1.logits", lg.numpy(), ol); check("s1.context", ctx.numpy(), oc)
            one = torch.randint(0, V1, (b, 1))
            check("s2.logits", S2(model)(ctx, one).numpy(), run_ort(P("s2.onnx"), {"context": ctx.numpy(), "s1_id": one.numpy()})[0])
            check("decoder", Dec(tok)(s1i, s2i).numpy(), run_ort(P("decoder.onnx"), {"s1": s1i.numpy(), "s2": s2i.numpy()})[0], tol=5e-3)

    names = ["encoder.onnx", "s1.onnx", "s2.onnx", "decoder.onnx"]
    manifest = {"name": f"kronos-{a.preset}", "version": 1, "source": mid, "tokenizer": tid,
                "s1_bits": model.s1_bits, "s2_bits": model.s2_bits, "d_model": D, "max_context": max_ctx, "clip": 5.0,
                "files": {n: {"sha256": sha256(P(n)), "size": os.path.getsize(P(n))} for n in names}}
    json.dump(manifest, open(P("manifest.json"), "w"), indent=2)
    with zipfile.ZipFile(P("kronos-onnx.zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for n in names + ["manifest.json"]: z.write(P(n), n)
    print("OK ->", P("kronos-onnx.zip"), f"({os.path.getsize(P('kronos-onnx.zip'))/1e6:.1f} MB)")

if __name__ == "__main__":
    main()
