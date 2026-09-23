//! Koil Gigatoken bridge.
//!
//! A persistent single-process adapter over the pinned upstream Gigatoken
//! crate (marcelroed/gigatoken 0.10.0, MIT). Koil talks to this process with
//! a compact length-prefixed binary protocol over stdio so tokenizer work
//! never happens on the Minecraft client thread and native tokenizers stay
//! loaded between requests.
//!
//! Request frame  (little-endian):
//!   u32 magic 0x4754_4F4B ("GTOK")
//!   u32 op
//!   u32 request_id
//!   u32 payload_len
//!   payload bytes
//!
//! Response frame (little-endian):
//!   u32 magic 0x4754_414B ("GTAK")
//!   u32 status (0 = ok, 1 = error)
//!   u32 request_id
//!   u32 payload_len
//!   payload bytes
//!
//! Ops:
//!   1 PING                  -> payload "gigatoken-bridge 0.10.0+fac0114b;loaded=<0|1>;vocab=<n>"
//!   2 LOAD_TOKENIZER_JSON   -> payload: raw tokenizer.json bytes; response: utf8 "ok <vocab>"
//!   3 ENCODE                -> payload: utf8 text; response: u32 count, then count u32 ids
//!   4 DECODE                -> payload: u32 count, then count u32 ids; response: utf8 bytes
//!   5 ENCODE_BATCH          -> payload: u32 count, then per item u32 len + utf8 bytes;
//!                              response: u32 count, then per item u32 n + n u32 ids
//!   6 SHUTDOWN              -> empty; exits the process after responding ok
//!   7 LOAD_COLIBRI_C_JSON   -> LOAD, but follows Colibri's current GLM C
//!                              tokenizer contract (NFC normalizer disabled)
//!
//! Added/special tokens from the tokenizer file are matched atomically in the
//! input, mirroring HuggingFace `encode(..., add_special_tokens=False)` for a
//! fully rendered prompt: no synthetic tokens are added, but special token
//! text already present (for example `<|im_start|>`) maps to its declared id.

use std::io::{self, Read, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};

use gigatoken_rs::load_tokenizer::hf::{load_hf_slice, HfTokenizer};

const REQ_MAGIC: u32 = 0x4754_4F4B;
const RES_MAGIC: u32 = 0x4754_414B;
const MAX_PAYLOAD: u64 = 512 * 1024 * 1024;
const MAX_TOKENS: u64 = 128 * 1024 * 1024;

const OP_PING: u32 = 1;
const OP_LOAD: u32 = 2;
const OP_ENCODE: u32 = 3;
const OP_DECODE: u32 = 4;
const OP_ENCODE_BATCH: u32 = 5;
const OP_SHUTDOWN: u32 = 6;
const OP_LOAD_COLIBRI_C: u32 = 7;

static LOADED: AtomicBool = AtomicBool::new(false);

struct State {
    /// The loaded upstream tokenizer, kept as the loader's enum directly:
    /// `HfTokenizer::Bpe` payload is `gigatoken_rs::Tokenizer` (public), the
    /// `SentencePiece` payload's type is public but unnameable downstream
    /// (its `bpe` module is crate-private) — matched and used via inference.
    tokenizer: Option<HfTokenizer>,
}

/// Full id-table size (base vocab + added-token ids), both variants.
fn id_space(tok: &HfTokenizer) -> usize {
    match tok {
        HfTokenizer::Bpe(t) => t.vocab_size(),
        HfTokenizer::SentencePiece(t) => t.vocab_size(),
    }
}

/// Base model vocab size in HF convention (id table minus the added-token
/// entries appended after the plain model vocab). The BPE variant exposes
/// each added token through `added_token_split_blockers`; the SentencePiece
/// variant has no public added-token accessor, so its full table is
/// reported (SentencePiece HF files keep added ids interleaved where the
/// base-id-count meaning no longer applies).
fn base_vocab(tok: &HfTokenizer) -> usize {
    match tok {
        HfTokenizer::Bpe(t) => t
            .vocab_size()
            .saturating_sub(t.added_token_split_blockers().len()),
        HfTokenizer::SentencePiece(t) => t.vocab_size(),
    }
}

fn encode_one(tok: &mut HfTokenizer, bytes: &[u8]) -> Result<Vec<u32>, String> {
    match tok {
        HfTokenizer::Bpe(t) => {
            let mut out = Vec::new();
            t.encode_with_added_tokens_flat(bytes, &mut out);
            Ok(out)
        }
        HfTokenizer::SentencePiece(t) => {
            let text =
                std::str::from_utf8(bytes).map_err(|e| format!("input is not utf-8: {e}"))?;
            // Decode tokens carry an unnameable-but-public `TokenId` type;
            // `From<TokenId> for u32` resolves through inference.
            Ok(t.encode_raw(text).into_iter().map(Into::into).collect())
        }
    }
}

fn decode_ids(tok: &HfTokenizer, ids: &[u32]) -> Result<Vec<u8>, String> {
    let space = id_space(tok);
    if let Some(&bad) = ids.iter().find(|&&id| id as usize >= space) {
        return Err(format!("token id {bad} out of range (id space {space})"));
    }
    // Both decode entry points take `&[TokenId]`; `TokenId: From<u32>` and
    // `#[repr(transparent)]`, and the slice element type is inferred from
    // the call site, so the conversion never names the crate-internal type.
    let tokens: Vec<_> = ids.iter().copied().map(Into::into).collect();
    match tok {
        HfTokenizer::Bpe(t) => Ok(t.decode(&tokens).collect()),
        HfTokenizer::SentencePiece(t) => Ok(t.decode(&tokens)),
    }
}

fn main() {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = stdin.lock();
    let mut output = io::BufWriter::new(stdout.lock());
    let mut state = State { tokenizer: None };

    let mut header = [0u8; 16];
    loop {
        if input.read_exact(&mut header).is_err() {
            return;
        }
        let magic = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        if magic != REQ_MAGIC {
            return;
        }
        let op = u32::from_le_bytes([header[4], header[5], header[6], header[7]]);
        let request_id = u32::from_le_bytes([header[8], header[9], header[10], header[11]]);
        let length = u32::from_le_bytes([header[12], header[13], header[14], header[15]]) as u64;
        if length > MAX_PAYLOAD {
            let _ = respond(&mut output, 1, request_id, b"payload too large");
            continue;
        }
        let mut payload = vec![0u8; length as usize];
        if input.read_exact(&mut payload).is_err() {
            return;
        }
        let result = catch_unwind(AssertUnwindSafe(|| handle(op, &payload, &mut state, request_id)));
        let (status, body) = match result {
            Ok(Ok(body)) => (0u32, body),
            Ok(Err(message)) => (1u32, message.into_bytes()),
            Err(_) => (1u32, b"internal panic in bridge".to_vec()),
        };
        if respond(&mut output, status, request_id, &body).is_err() {
            return;
        }
        if op == OP_SHUTDOWN {
            return;
        }
    }
}

fn respond(output: &mut impl Write, status: u32, request_id: u32, payload: &[u8]) -> io::Result<()> {
    let mut header = [0u8; 16];
    header[0..4].copy_from_slice(&RES_MAGIC.to_le_bytes());
    header[4..8].copy_from_slice(&status.to_le_bytes());
    header[8..12].copy_from_slice(&request_id.to_le_bytes());
    header[12..16].copy_from_slice(&(payload.len() as u32).to_le_bytes());
    output.write_all(&header)?;
    output.write_all(payload)?;
    output.flush()
}

fn handle(op: u32, payload: &[u8], state: &mut State, request_id: u32) -> Result<Vec<u8>, String> {
    match op {
        OP_PING => Ok(format!(
            "gigatoken-bridge 0.10.0+fac0114b;loaded={};vocab={}",
            LOADED.load(Ordering::Relaxed) as u8,
            state.tokenizer.as_ref().map(base_vocab).unwrap_or(0)
        )
        .into_bytes()),
        OP_LOAD | OP_LOAD_COLIBRI_C => {
            let mut tokenizer = load_hf_slice(payload)
                .map_err(|e| format!("failed to load tokenizer.json: {e}"))?;
            if op == OP_LOAD_COLIBRI_C {
                match &mut tokenizer {
                    HfTokenizer::Bpe(tokenizer) => tokenizer.set_normalize_nfc(false),
                    HfTokenizer::SentencePiece(_) => {
                        return Err("Colibri-C compatibility mode currently supports BPE tokenizers only".to_string())
                    }
                }
            }
            let vocab = base_vocab(&tokenizer);
            state.tokenizer = Some(tokenizer);
            LOADED.store(true, Ordering::Relaxed);
            eprintln!("[GigaToken native] operation=load status=success request={} vocab={} colibriContract={}",
                request_id, vocab, op == OP_LOAD_COLIBRI_C);
            Ok(format!("ok {vocab}").into_bytes())
        }
        OP_ENCODE => {
            let tokenizer = state.tokenizer.as_mut().ok_or("no tokenizer loaded")?;
            let ids = encode_one(tokenizer, payload)?;
            eprintln!("[GigaToken native] operation=encode status=success request={} inputBytes={} outputTokens={}",
                request_id, payload.len(), ids.len());
            let mut out = Vec::with_capacity(4 + ids.len() * 4);
            out.extend_from_slice(&(ids.len() as u32).to_le_bytes());
            for id in ids {
                out.extend_from_slice(&id.to_le_bytes());
            }
            Ok(out)
        }
        OP_DECODE => {
            let tokenizer = state.tokenizer.as_ref().ok_or("no tokenizer loaded")?;
            let ids = read_ids(payload)?;
            let decoded = decode_ids(tokenizer, &ids)?;
            eprintln!("[GigaToken native] operation=decode status=success request={} inputTokens={} outputBytes={}",
                request_id, ids.len(), decoded.len());
            Ok(decoded)
        }
        OP_ENCODE_BATCH => {
            let tokenizer = state.tokenizer.as_mut().ok_or("no tokenizer loaded")?;
            let mut cursor = 0usize;
            let count = read_u32(payload, &mut cursor)? as usize;
            let mut texts = Vec::with_capacity(count);
            for _ in 0..count {
                let len = read_u32(payload, &mut cursor)? as usize;
                if cursor + len > payload.len() {
                    return Err("batch payload truncated".to_string());
                }
                texts.push(&payload[cursor..cursor + len]);
                cursor += len;
            }
            let mut out = Vec::new();
            out.extend_from_slice(&(count as u32).to_le_bytes());
            for text in texts {
                let ids = encode_one(tokenizer, text)?;
                out.extend_from_slice(&(ids.len() as u32).to_le_bytes());
                for id in ids {
                    out.extend_from_slice(&id.to_le_bytes());
                }
            }
            Ok(out)
        }
        OP_SHUTDOWN => Ok(b"bye".to_vec()),
        other => Err(format!("unknown op {other}")),
    }
}

fn read_u32(payload: &[u8], cursor: &mut usize) -> Result<u32, String> {
    if *cursor + 4 > payload.len() {
        return Err("payload truncated".to_string());
    }
    let value = u32::from_le_bytes([
        payload[*cursor],
        payload[*cursor + 1],
        payload[*cursor + 2],
        payload[*cursor + 3],
    ]);
    *cursor += 4;
    Ok(value)
}

fn read_ids(payload: &[u8]) -> Result<Vec<u32>, String> {
    let mut cursor = 0usize;
    let count = read_u32(payload, &mut cursor)? as u64;
    if count > MAX_TOKENS {
        return Err("too many tokens".to_string());
    }
    let mut ids = Vec::with_capacity(count as usize);
    for _ in 0..count {
        ids.push(read_u32(payload, &mut cursor)?);
    }
    Ok(ids)
}
