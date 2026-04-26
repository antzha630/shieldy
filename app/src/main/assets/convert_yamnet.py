#!/usr/bin/env python3
"""Convert YAMNet from TensorFlow Hub to ONNX format for Zetic."""

import tensorflow as tf
import tensorflow_hub as hub
import numpy as np
import subprocess
import os

print("Loading YAMNet from TensorFlow Hub...")
model = hub.load('https://tfhub.dev/google/yamnet/1')

# Get the concrete function for the waveform input
# YAMNet expects a 1D waveform of 16kHz audio samples
INPUT_SIZE = 15600  # ~0.975 seconds at 16kHz (matching our TFLite model)

print(f"Creating SavedModel with input size {INPUT_SIZE}...")

# Create a wrapper that accepts batched input
class YAMNetWrapper(tf.Module):
    def __init__(self, yamnet_model):
        super().__init__()
        self.yamnet = yamnet_model
    
    @tf.function(input_signature=[tf.TensorSpec(shape=[None, INPUT_SIZE], dtype=tf.float32)])
    def __call__(self, waveform):
        # YAMNet expects 1D input, so we process each batch item
        # For simplicity, we'll just take the first batch item
        scores, embeddings, log_mel_spectrogram = self.yamnet(waveform[0])
        # Return just the class scores (shape: [frames, 521])
        # Average across frames to get single prediction
        avg_scores = tf.reduce_mean(scores, axis=0, keepdims=True)
        return avg_scores

wrapper = YAMNetWrapper(model)

# Save as SavedModel
saved_model_path = "yamnet_saved_model"
print(f"Saving to {saved_model_path}...")
tf.saved_model.save(wrapper, saved_model_path)

# Create sample input for Zetic
print("Creating sample input waveform.npy...")
sample_waveform = np.sin(2 * np.pi * 440 * np.linspace(0, INPUT_SIZE/16000, INPUT_SIZE))
sample_waveform = sample_waveform.astype(np.float32)
sample_waveform = np.expand_dims(sample_waveform, axis=0)  # Shape: [1, 15600]
np.save('waveform.npy', sample_waveform)
print(f"Created waveform.npy with shape {sample_waveform.shape}")

# Convert to ONNX using tf2onnx
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
    print("Success! Created yamnet.onnx")
    
    # Verify the files
    if os.path.exists('yamnet.onnx'):
        size = os.path.getsize('yamnet.onnx')
        print(f"yamnet.onnx size: {size / 1024 / 1024:.2f} MB")
    if os.path.exists('waveform.npy'):
        data = np.load('waveform.npy')
        print(f"waveform.npy shape: {data.shape}, dtype: {data.dtype}")
