"""Real pinned Qwen runtime check on CPU; no GPU or translation-quality claim."""
import importlib.metadata
from pathlib import Path
import torch
import bitsandbytes
from transformers import AutoModelForCausalLM, BitsAndBytesConfig, Qwen3Config

assert torch.__version__.split('+')[0] == '2.11.0'
assert importlib.metadata.version('bitsandbytes') == '0.48.2'
# The older 0.45.5 distribution lacks this CUDA 13 binary.
assert (Path(bitsandbytes.__file__).parent / 'libbitsandbytes_cuda130.so').is_file()
quantization = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type='nf4',
    bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.float16)
assert quantization.bnb_4bit_quant_type == 'nf4'
# Exercise the real Qwen3 class, save/load path, and generation APIs with small
# random weights, avoiding the user's multi-GB model in a CPU CI job.
import tempfile
torch.manual_seed(7)
config = Qwen3Config(vocab_size=128, hidden_size=64, intermediate_size=128,
    num_hidden_layers=1, num_attention_heads=4, num_key_value_heads=2,
    head_dim=16, max_position_embeddings=128, eos_token_id=2, pad_token_id=0)
with tempfile.TemporaryDirectory() as directory:
    model = AutoModelForCausalLM.from_config(config)
    model.save_pretrained(directory)
    model = AutoModelForCausalLM.from_pretrained(directory, local_files_only=True,
        trust_remote_code=False, torch_dtype=torch.float32).eval()
    with torch.inference_mode():
        inputs = torch.tensor([[1, 3, 4]])
        generated = model.generate(inputs, attention_mask=torch.ones_like(inputs),
            max_new_tokens=4, do_sample=False, pad_token_id=0)
    assert generated.shape[0] == 1 and 3 < generated.shape[1] <= 7
print('PASS: torch 2.11 + pinned Transformers Qwen3 save/load/generation, NF4 configuration and CUDA 13 binary presence. CPU/random weights only; CUDA NF4 requires a GPU.')
