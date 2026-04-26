#!/usr/bin/env python3
"""Convert locally downloaded YAMNet to ONNX format for Zetic."""

import tensorflow as tf
import numpy as np
import subprocess
import os

INPUT_SIZE = 15600  # ~0.975 seconds at 16kHz

print("Loading YAMNet from local directory...")
model = tf.saved_model.load('yamnet_hub')

print(f"Creating wrapper with input size {INPUT_SIZE}...")

class YAMNetWrapper(tf.Module):
    def __init__(self, yamnet_model):
        super().__init__()
        self.yamnet = yamnet_model
    
    @tf.function(input_signature=[tf.TensorSpec(shape=[1, INPUT_SIZE], dtype=tf.float32)])
    def __call__(self, waveform):
        scores, embeddings, log_mel_spectrogram = self.yamnet(waveform[0])
        avg_scores = tf.reduce_mean(scores, axis=0, keepdims=True)
        return avg_scores

wrapper = YAMNetWrapper(model)

saved_model_path = "yamnet_wrapped"
print(f"Saving wrapped model to {saved_model_path}...")
os.makedirs(saved_model_path, exist_ok=True)
tf.saved_model.save(wrapper, saved_model_path)

print("Creating sample input waveform.npy...")
sample_waveform = np.sin(2 * np.pi * 440 * np.linspace(0, INPUT_SIZE/16000, INPUT_SIZE))
sample_waveform = sample_waveform.astype(np.float32)
sample_waveform = np.expand_dims(sample_waveform, axis=0)
np.save('waveform.npy', sample_waveform)
print(f"Created waveform.npy with shape {sample_waveform.shape}")

print("Converting to ONNX...")
result = subprocess.run([
    'python3', '-m', 'tf2onnx.convert',
    '--saved-model', saved_model_path,
    '--output', 'yamnet.onnx',
    '--opset', '13'
], capture_output=True, text=True)

print(result.stdout)
if result.returncode != 0:
    print("STDERR:", result.stderr)
else:
    print("Success!")
    
if os.path.exists('yamnet.onnx'):
    size = os.path.getsize('yamnet.onnx')
    print(f"yamnet.onnx size: {size / 1024 / 1024:.2f} MB")
if os.path.exists('waveform.npy'):
    data = np.load('waveform.npy')
    print(f"waveform.npy shape: {data.shape}, dtype: {data.dtype}")
