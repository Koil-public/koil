use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

use jni::objects::{JClass, JFloatArray, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use turbovec::IdMapIndex;

const BRIDGE_VERSION: &str = "koil-turbovec-bridge/1.0.0 turbovec/1.0.0";

struct NativeIndex {
    index: IdMapIndex,
}

fn fail(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
}

unsafe fn native_index<'a>(handle: jlong) -> Result<&'a mut NativeIndex, String> {
    if handle == 0 {
        return Err("TurboVec bridge handle is closed".to_owned());
    }
    Ok(&mut *(handle as *mut NativeIndex))
}

fn floats(env: &mut JNIEnv, values: JFloatArray) -> Result<Vec<f32>, String> {
    let length = env
        .get_array_length(&values)
        .map_err(|error| error.to_string())?;
    let mut output = vec![0.0_f32; length as usize];
    env.get_float_array_region(&values, 0, &mut output)
        .map_err(|error| error.to_string())?;
    if output.iter().any(|value| !value.is_finite()) {
        return Err("TurboVec vectors must be finite".to_owned());
    }
    Ok(output)
}

fn longs(env: &mut JNIEnv, values: JLongArray) -> Result<Vec<u64>, String> {
    let length = env
        .get_array_length(&values)
        .map_err(|error| error.to_string())?;
    let mut raw = vec![0_i64; length as usize];
    env.get_long_array_region(&values, 0, &mut raw)
        .map_err(|error| error.to_string())?;
    raw.into_iter()
        .map(|value| {
            if value > 0 {
                Ok(value as u64)
            } else {
                Err("TurboVec IDs must be positive".to_owned())
            }
        })
        .collect()
}

fn path(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_createNative(
    mut env: JNIEnv,
    _class: JClass,
    dimensions: jint,
    bit_width: jint,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if dimensions <= 0 || bit_width <= 0 {
            return Err("TurboVec dimensions and bit width must be positive".to_owned());
        }
        IdMapIndex::new(dimensions as usize, bit_width as usize)
            .map(|index| Box::into_raw(Box::new(NativeIndex { index })) as jlong)
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(error)) => {
            fail(&mut env, error);
            0
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while creating an index");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_loadNative(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let value = path(&mut env, value)?;
        IdMapIndex::load(Path::new(&value))
            .map(|index| Box::into_raw(Box::new(NativeIndex { index })) as jlong)
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(error)) => {
            fail(&mut env, error);
            0
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while loading an index");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_addNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jlong,
    values: JFloatArray,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if id <= 0 {
            return Err("TurboVec IDs must be positive".to_owned());
        }
        let vector = floats(&mut env, values)?;
        let index = unsafe { native_index(handle)? };
        index
            .index
            .add_with_ids(&vector, &[id as u64])
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => fail(&mut env, error),
        Err(_) => fail(&mut env, "TurboVec bridge panicked while adding a vector"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_removeNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jlong,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if id <= 0 {
            return Err("TurboVec IDs must be positive".to_owned());
        }
        let index = unsafe { native_index(handle)? };
        Ok::<bool, String>(index.index.remove(id as u64))
    }));
    match result {
        Ok(Ok(true)) => JNI_TRUE,
        Ok(Ok(false)) => JNI_FALSE,
        Ok(Err(error)) => {
            fail(&mut env, error);
            JNI_FALSE
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while removing a vector");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_containsNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jlong,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if id <= 0 {
            return Err("TurboVec IDs must be positive".to_owned());
        }
        let index = unsafe { native_index(handle)? };
        Ok::<bool, String>(index.index.contains(id as u64))
    }));
    match result {
        Ok(Ok(true)) => JNI_TRUE,
        Ok(Ok(false)) => JNI_FALSE,
        Ok(Err(error)) => {
            fail(&mut env, error);
            JNI_FALSE
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while checking an ID");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_searchNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JFloatArray,
    limit: jint,
    allowed_ids: JLongArray,
) -> jlongArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if limit <= 0 {
            return Err("TurboVec search limit must be positive".to_owned());
        }
        let query = floats(&mut env, query)?;
        let allowed = longs(&mut env, allowed_ids)?;
        let index = unsafe { native_index(handle)? };
        let result = index
            .index
            .try_search_with_allowlist(
                &query,
                limit as usize,
                if allowed.is_empty() {
                    None
                } else {
                    Some(&allowed)
                },
            )
            .map_err(|error| error.to_string())?;
        let mut packed = Vec::with_capacity(result.ids.len() * 2);
        for (id, score) in result.ids.into_iter().zip(result.scores.into_iter()) {
            packed.push(id as jlong);
            packed.push(score.to_bits() as jlong);
        }
        Ok::<Vec<jlong>, String>(packed)
    }));
    let packed = match result {
        Ok(Ok(packed)) => packed,
        Ok(Err(error)) => {
            fail(&mut env, error);
            return std::ptr::null_mut();
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while searching");
            return std::ptr::null_mut();
        }
    };
    match env.new_long_array(packed.len() as i32) {
        Ok(array) => {
            if let Err(error) = env.set_long_array_region(&array, 0, &packed) {
                fail(&mut env, error.to_string());
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(error) => {
            fail(&mut env, error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_syncNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    value: JString,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let value = path(&mut env, value)?;
        let index = unsafe { native_index(handle)? };
        index
            .index
            .sync(Path::new(&value))
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => fail(&mut env, error),
        Err(_) => fail(&mut env, "TurboVec bridge panicked while syncing"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_calibrateNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sample: JFloatArray,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let sample = floats(&mut env, sample)?;
        let index = unsafe { native_index(handle)? };
        index
            .index
            .calibrate(&sample)
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => fail(&mut env, error),
        Err(_) => fail(&mut env, "TurboVec bridge panicked while calibrating"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_statsNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let index = unsafe { native_index(handle)? };
        Ok::<Vec<jlong>, String>(vec![
            index.index.len() as jlong,
            index.index.dim_opt().unwrap_or(0) as jlong,
            index.index.bit_width() as jlong,
        ])
    }));
    let values = match result {
        Ok(Ok(values)) => values,
        Ok(Err(error)) => {
            fail(&mut env, error);
            return std::ptr::null_mut();
        }
        Err(_) => {
            fail(&mut env, "TurboVec bridge panicked while reading stats");
            return std::ptr::null_mut();
        }
    };
    match env.new_long_array(values.len() as i32) {
        Ok(array) => {
            if let Err(error) = env.set_long_array_region(&array, 0, &values) {
                fail(&mut env, error.to_string());
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(error) => {
            fail(&mut env, error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_versionNative(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    match env.new_string(BRIDGE_VERSION) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            fail(&mut env, error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_spirit_koil_api_model_retrieval_TurboVecNativeBridge_closeNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut NativeIndex));
        }
    }
}
