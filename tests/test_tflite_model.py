import os
import time
import numpy as np
import tensorflow as tf

MODEL_PATH = r"e:\GenAI-part2-Rag-implementation-main\PervasiveSense_Model\deepsense_int8.tflite"

def print_result(test_name, passed, details=""):
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] {test_name}")
    if details:
        print(f"       {details}")

def run_tests():
    print("Starting TFLite Model Testing Suite...")
    
    # 9. Verify model file size
    try:
        file_size = os.path.getsize(MODEL_PATH)
        is_reasonable = 10 * 1024 < file_size < 100 * 1024 * 1024 # 10KB to 100MB
        print_result("Model File Size Check", is_reasonable, f"Size: {file_size / 1024:.2f} KB")
    except Exception as e:
        print_result("Model File Size Check", False, str(e))
        return

    # 1. Load model and verify shapes
    try:
        interpreter = tf.lite.Interpreter(model_path=MODEL_PATH)
        interpreter.allocate_tensors()
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        
        expected_in_shape = [1, 128, 6]
        expected_out_shape = [1, 8]
        
        in_shape_match = list(input_details[0]['shape']) == expected_in_shape
        out_shape_match = list(output_details[0]['shape']) == expected_out_shape
        
        print_result("Load Model & Shape Check", in_shape_match and out_shape_match, 
                     f"In: {input_details[0]['shape']}, Out: {output_details[0]['shape']}")
        
        input_index = input_details[0]['index']
        output_index = output_details[0]['index']
        input_dtype = input_details[0]['dtype']
        output_dtype = output_details[0]['dtype']
        
        # Determine if quantization requires scaling
        in_scale, in_zero_point = input_details[0]['quantization']
        out_scale, out_zero_point = output_details[0]['quantization']
        
    except Exception as e:
        print_result("Load Model & Shape Check", False, str(e))
        return

    def run_inference(data_float32):
        if input_dtype == np.int8:
            input_data = (data_float32 / in_scale + in_zero_point).astype(np.int8)
        else:
            input_data = data_float32.astype(input_dtype)
            
        interpreter.set_tensor(input_index, input_data)
        interpreter.invoke()
        output_data = interpreter.get_tensor(output_index)
        
        if output_dtype == np.int8:
            output_data = (output_data.astype(np.float32) - out_zero_point) * out_scale
            
        return output_data

    # 7. Softmax Validity Helper
    def check_softmax(probs):
        total = np.sum(probs)
        return np.isclose(total, 1.0, atol=1e-2), total

    # 3. Test ZERO input
    try:
        zero_input = np.zeros(expected_in_shape, dtype=np.float32)
        zero_out = run_inference(zero_input)
        pred_class = np.argmax(zero_out[0])
        valid_softmax, total = check_softmax(zero_out[0])
        
        print_result("ZERO Input Test", pred_class == 0 and valid_softmax, 
                     f"Predicted Class: {pred_class} (Expected: 0), Sum: {total:.4f}")
    except Exception as e:
        print_result("ZERO Input Test", False, str(e))

    # 4. Test RANDOM NOISE
    try:
        random_input = np.random.randn(*expected_in_shape).astype(np.float32)
        random_out = run_inference(random_input)
        valid_softmax, total = check_softmax(random_out[0])
        
        print_result("RANDOM NOISE Test", valid_softmax, f"Sum of probabilities: {total:.4f}")
    except Exception as e:
        print_result("RANDOM NOISE Test", False, str(e))

    # 5. Test EXTREME values
    try:
        extreme_input = np.ones(expected_in_shape, dtype=np.float32) * 1e6
        extreme_out = run_inference(extreme_input)
        valid_softmax, total = check_softmax(extreme_out[0])
        
        negative_extreme = np.ones(expected_in_shape, dtype=np.float32) * -1e6
        neg_ext_out = run_inference(negative_extreme)
        
        print_result("EXTREME Values Test", True, "No crash on extreme positive/negative inputs")
    except Exception as e:
        print_result("EXTREME Values Test", False, str(e))

    # 6. Test NaN and Inf
    try:
        nan_input = np.full(expected_in_shape, np.nan, dtype=np.float32)
        nan_out = run_inference(nan_input)
        
        inf_input = np.full(expected_in_shape, np.inf, dtype=np.float32)
        inf_out = run_inference(inf_input)
        
        print_result("NaN and Inf Test", True, "No crash on NaN/Inf inputs")
    except Exception as e:
        print_result("NaN and Inf Test", False, str(e))

    # 10. Batch Consistency (Determinism)
    try:
        test_input = np.random.randn(*expected_in_shape).astype(np.float32)
        out1 = run_inference(test_input)
        out2 = run_inference(test_input)
        is_consistent = np.allclose(out1, out2)
        print_result("Determinism Test", is_consistent)
    except Exception as e:
        print_result("Determinism Test", False, str(e))

    # 2. Test 8 Classes with synthetic data (dummy check to see if it predicts something)
    try:
        # Just generating some synthetic patterns to see if predictions vary or sum to 1
        all_passed = True
        for i in range(8):
            # Synthetic sine wave pattern
            t = np.linspace(0, 10, 128)
            freq = (i + 1) * 2
            synth = np.sin(t * freq)[:, None] * np.ones((1, 6))
            synth_input = synth.reshape(1, 128, 6).astype(np.float32)
            
            out = run_inference(synth_input)
            valid_softmax, total = check_softmax(out[0])
            if not valid_softmax:
                all_passed = False
                print_result(f"Synthetic Class {i} Test", False, f"Invalid softmax sum: {total:.4f}")
                
        print_result("Synthetic Data 8-Class Test", all_passed, "Evaluated softmax validity for diverse inputs")
    except Exception as e:
        print_result("Synthetic Data 8-Class Test", False, str(e))

    # 8. Inference Speed
    try:
        speed_input = np.random.randn(*expected_in_shape).astype(np.float32)
        
        # Warmup
        for _ in range(10):
            run_inference(speed_input)
            
        start_time = time.time()
        num_inferences = 100
        for _ in range(num_inferences):
            run_inference(speed_input)
        end_time = time.time()
        
        avg_time_ms = (end_time - start_time) / num_inferences * 1000
        print_result("Inference Speed Test", True, f"Average time over 100 runs: {avg_time_ms:.2f} ms")
    except Exception as e:
        print_result("Inference Speed Test", False, str(e))

if __name__ == "__main__":
    run_tests()
