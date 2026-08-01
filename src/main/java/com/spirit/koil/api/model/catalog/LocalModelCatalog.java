package com.spirit.koil.api.model.catalog;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public final class LocalModelCatalog {
        private static final long GIB = 1024L * 1024L * 1024L;
        private static final String PROVIDER = "llama_cpp";
        private static final String RUNTIME = "llama.cpp-b10173";
        private static final List<LocalModelCatalogEntry> ENTRIES = List.of(
                qwen(
                        "qwen1.5-0.5b-q4",
                        "Qwen1.5 0.5B Chat",
                        "qwen1.5-0.5b-chat",
                        "0.46B",
                        "Tongyi Qianwen Research",
                        8,
                        2L * GIB,
                        4L * GIB,
                        "Legacy tiny Qwen chat model. Useful for compatibility testing and very basic conversation.",
                        artifact(
                                "Qwen/Qwen1.5-0.5B-Chat-GGUF",
                                "qwen1_5-0_5b-chat-q4_k_m.gguf",
                                407_155_552L,
                                "92916b71d32f5afea48fb7383e3b48c5b1c111f5a59f0b83c764ea1d07fe1a3a"
                        )
                ),
                qwen(
                        "qwen1.5-1.8b-q4",
                        "Qwen1.5 1.8B Chat",
                        "qwen1.5-1.8b-chat",
                        "1.84B",
                        "Tongyi Qianwen",
                        17,
                        4L * GIB,
                        6L * GIB,
                        "Legacy compact Qwen chat model with better language coverage than the 0.5B tier.",
                        artifact(
                                "Qwen/Qwen1.5-1.8B-Chat-GGUF",
                                "qwen1_5-1_8b-chat-q4_k_m.gguf",
                                1_217_752_928L,
                                "702e983c77883426806a2af75d34ab3e462e1b822f9dc23b49e02280c24b2b18"
                        )
                ),
                qwen(
                        "qwen1.5-4b-q4",
                        "Qwen1.5 4B Chat",
                        "qwen1.5-4b-chat",
                        "3.95B",
                        "Tongyi Qianwen",
                        25,
                        5L * GIB,
                        8L * GIB,
                        "Legacy mid-size Qwen chat model retained for compatibility and comparison.",
                        artifact(
                                "Qwen/Qwen1.5-4B-Chat-GGUF",
                                "qwen1_5-4b-chat-q4_k_m.gguf",
                                2_455_115_872L,
                                "426143ccd3241b9547c2b70c622b4f4ef3436ee07e44991bd69ad84b36cd9b9b"
                        )
                ),
                qwen(
                        "qwen1.5-7b-q4",
                        "Qwen1.5 7B Chat",
                        "qwen1.5-7b-chat",
                        "7.72B",
                        "Tongyi Qianwen",
                        33,
                        8L * GIB,
                        12L * GIB,
                        "Legacy general Qwen chat model. New installations should normally prefer Qwen2.5 or Qwen3.",
                        artifact(
                                "Qwen/Qwen1.5-7B-Chat-GGUF",
                                "qwen1_5-7b-chat-q4_k_m.gguf",
                                4_767_104_224L,
                                "d7f132b1eff9ce35acf8e83ab96d2bc87eaedb68244e467bbc99e9f46a122a4c"
                        )
                ),
                qwen(
                        "qwen1.5-14b-q4",
                        "Qwen1.5 14B Chat",
                        "qwen1.5-14b-chat",
                        "14.2B",
                        "Tongyi Qianwen",
                        42,
                        14L * GIB,
                        20L * GIB,
                        "Legacy larger Qwen chat model retained for reproducibility and older-model comparisons.",
                        artifact(
                                "Qwen/Qwen1.5-14B-Chat-GGUF",
                                "qwen1_5-14b-chat-q4_k_m.gguf",
                                9_191_034_336L,
                                "46fbff2797c39c2d6aa555db0b0b4fe3f41b712a9b45266e438aa9a5047c0563"
                        )
                ),
                qwen(
                        "qwen1.5-32b-q4",
                        "Qwen1.5 32B Chat",
                        "qwen1.5-32b-chat",
                        "32.5B",
                        "Tongyi Qianwen",
                        50,
                        26L * GIB,
                        40L * GIB,
                        "Legacy high-memory Qwen chat model. Modern Qwen generations provide better efficiency.",
                        artifact(
                                "Qwen/Qwen1.5-32B-Chat-GGUF",
                                "qwen1_5-32b-chat-q4_k_m.gguf",
                                19_698_962_112L,
                                "9f5b913fef9f0bed095bc948b35515290a152ed1c7f07ea2c3ddd6f8877ce1e1"
                        )
                ),
                qwen(
                        "qwen1.5-72b-q3",
                        "Qwen1.5 72B Chat",
                        "qwen1.5-72b-chat",
                        "72.7B",
                        "Q3_K_M",
                        "Tongyi Qianwen",
                        57,
                        42L * GIB,
                        56L * GIB,
                        "Legacy 72B model. The official GGUF repository provides Q3_K_M rather than Q4_K_M.",
                        artifact(
                                "Qwen/Qwen1.5-72B-Chat-GGUF",
                                "qwen1_5-72b-chat-q3_k_m.gguf",
                                35_927_611_744L,
                                "4a7e8e7a61e5e16774c2fb8c8c839b2d0f794352f76590a21d9b12b43c2260d4"
                        )
                ),
                qwen(
                        "qwen1.5-110b-q2",
                        "Qwen1.5 110B Chat",
                        "qwen1.5-110b-chat",
                        "110B",
                        "Q2_K",
                        "Tongyi Qianwen",
                        61,
                        48L * GIB,
                        64L * GIB,
                        "Legacy 110B model. Its official GGUF is Q2_K, so newer smaller models may provide better practical quality.",
                        artifact(
                                "Qwen/Qwen1.5-110B-Chat-GGUF",
                                "qwen1_5-110b-chat-q2_k.gguf",
                                41_175_701_952L,
                                "06129a5c75a82b76df722aabd6442bad0fa796a567f1b7e8c4f66fe87d35be11"
                        )
                ),
                qwen(
                        "codeqwen1.5-7b-q4",
                        "CodeQwen1.5 7B Chat",
                        "codeqwen1.5-7b-chat",
                        "7.7B",
                        "Tongyi Qianwen",
                        36,
                        8L * GIB,
                        12L * GIB,
                        "Legacy code-specialized Qwen model retained for older coding workflows.",
                        artifact(
                                "Qwen/CodeQwen1.5-7B-Chat-GGUF",
                                "codeqwen-1_5-7b-chat-q4_k_m.gguf",
                                4_738_590_464L,
                                "98572d2cbc355c6be6c89b431df5c26c5bc2838dc755b53fe5f81eb9fa19df3c"
                        )
                ),
                qwen(
                        "qwen2-0.5b-q4",
                        "Qwen2 0.5B Instruct",
                        "qwen2-0.5b-instruct",
                        "0.49B",
                        "Apache-2.0",
                        10,
                        2L * GIB,
                        4L * GIB,
                        "Small Qwen2 instruction model for basic chat and low-storage compatibility.",
                        artifact(
                                "Qwen/Qwen2-0.5B-Instruct-GGUF",
                                "qwen2-0_5b-instruct-q4_k_m.gguf",
                                397_805_248L,
                                "f0a42bb979ca62b5e61f3bf924ab4b6a40aa091825ee7dcb4039949980ab81a8"
                        )
                ),
                qwen(
                        "qwen2-1.5b-q4",
                        "Qwen2 1.5B Instruct",
                        "qwen2-1.5b-instruct",
                        "1.54B",
                        "Apache-2.0",
                        20,
                        3L * GIB,
                        6L * GIB,
                        "Compact Qwen2 instruction model for general chat and simple structured work.",
                        artifact(
                                "Qwen/Qwen2-1.5B-Instruct-GGUF",
                                "qwen2-1_5b-instruct-q4_k_m.gguf",
                                986_045_824L,
                                "f521a15453fd7f820e8467f4a307c99e44f5ab9cc24273d2fe67cd7cb1288f05"
                        )
                ),
                qwen(
                        "qwen2-7b-q4",
                        "Qwen2 7B Instruct",
                        "qwen2-7b-instruct",
                        "7.62B",
                        "Apache-2.0",
                        40,
                        8L * GIB,
                        12L * GIB,
                        "General Qwen2 instruction model with official llama.cpp-compatible GGUF.",
                        artifact(
                                "Qwen/Qwen2-7B-Instruct-GGUF",
                                "qwen2-7b-instruct-q4_k_m.gguf",
                                4_683_071_264L,
                                "ed93dfc426f926451fa3ec7f996a787a31cfd97e55d7769568fbffc2d69861c2"
                        )
                ),
                qwen(
                        "qwen2-57b-a14b-q4",
                        "Qwen2 57B-A14B Instruct",
                        "qwen2-57b-a14b-instruct",
                        "57.4B / 14B active",
                        "Apache-2.0",
                        62,
                        40L * GIB,
                        52L * GIB,
                        "Qwen2 mixture-of-experts model with a large storage footprint and roughly 14B active parameters.",
                        artifact(
                                "Qwen/Qwen2-57B-A14B-Instruct-GGUF",
                                "qwen2-57b-a14b-instruct-q4_k_m.gguf",
                                34_853_272_896L,
                                "e389c70672a4c4167ac3a8227b96d3e447c49983d66802719ad422658a9af5cb"
                        )
                ),
                qwen(
                        "qwen2-72b-q4",
                        "Qwen2 72B Instruct",
                        "qwen2-72b-instruct",
                        "72.7B",
                        "Qwen",
                        67,
                        52L * GIB,
                        64L * GIB,
                        "Largest official Qwen2 instruction GGUF; intended for high-memory systems.",
                        artifact(
                                "Qwen/Qwen2-72B-Instruct-GGUF",
                                "qwen2-72b-instruct-q4_k_m.gguf",
                                47_415_712_640L,
                                "ae7826a1318924c1f597dea47d071d70d25018187c44e76624e577da7e30be59"
                        )
                ),
                qwen(
                        "qwen2.5-0.5b-q4",
                        "Qwen2.5 0.5B",
                        "qwen2.5-0.5b",
                        "0.49B",
                        "Apache-2.0",
                        12,
                        2L * GIB,
                        4L * GIB,
                        "Smallest download and fastest startup; suitable for basic chat and simple tool selection.",
                        artifact(
                                "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                                491_400_032L,
                                "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
                        )
                ),
                qwen(
                        "qwen2.5-1.5b-q4",
                        "Qwen2.5 1.5B",
                        "qwen2.5-1.5b",
                        "1.54B",
                        "Apache-2.0",
                        22,
                        3L * GIB,
                        6L * GIB,
                        "Compact general model with better instruction following than the 0.5B option.",
                        artifact(
                                "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
                                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                                1_117_320_736L,
                                "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
                        )
                ),
                qwen(
                        "qwen2.5-3b-q4",
                        "Qwen2.5 3B",
                        "qwen2.5-3b",
                        "3.09B",
                        "Qwen Research",
                        34,
                        5L * GIB,
                        8L * GIB,
                        "Balanced local choice for chat, formatting, and model-driven automation.",
                        artifact(
                                "Qwen/Qwen2.5-3B-Instruct-GGUF",
                                "qwen2.5-3b-instruct-q4_k_m.gguf",
                                2_104_932_768L,
                                "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d"
                        )
                ),
                qwen(
                        "qwen2.5-7b-q4",
                        "Qwen2.5 7B",
                        "qwen2.5-7b",
                        "7.61B",
                        "Apache-2.0",
                        52,
                        8L * GIB,
                        12L * GIB,
                        "Higher-quality local model; split into two verified model files.",
                        artifact(
                                "Qwen/Qwen2.5-7B-Instruct-GGUF",
                                "qwen2.5-7b-instruct-q4_k_m-00001-of-00002.gguf",
                                3_993_201_344L,
                                "dfce12e3862a5283ccfb88221b48480e58745165de856439950d0f22590580db"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-7B-Instruct-GGUF",
                                "qwen2.5-7b-instruct-q4_k_m-00002-of-00002.gguf",
                                689_872_288L,
                                "539cf93f78e887edea1c04e2d7d8cdaca9d01dae9c9025bcb8accbe29df3d72a"
                        )
                ),
                qwen(
                        "qwen2.5-14b-q4",
                        "Qwen2.5 14B",
                        "qwen2.5-14b",
                        "14.7B",
                        "Apache-2.0",
                        65,
                        14L * GIB,
                        20L * GIB,
                        "Largest built-in compact option; intended for systems with more memory and patience.",
                        artifact(
                                "Qwen/Qwen2.5-14B-Instruct-GGUF",
                                "qwen2.5-14b-instruct-q4_k_m-00001-of-00003.gguf",
                                3_991_999_872L,
                                "a09ea5e7b1eafb1b30b241726c3cc3c905c96f14ad41e246ffa5f44e53904f68"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-14B-Instruct-GGUF",
                                "qwen2.5-14b-instruct-q4_k_m-00002-of-00003.gguf",
                                3_989_373_504L,
                                "21b9457d079680d284e90ef69607c4b2d8ef64a09d4729cb7b5e1357bdba41ae"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-14B-Instruct-GGUF",
                                "qwen2.5-14b-instruct-q4_k_m-00003-of-00003.gguf",
                                1_006_737_120L,
                                "c8d37006760a387a35216e070e6664d7da927f10be8eb870fef2e3d4833d9976"
                        )
                ),
                qwen(
                        "qwen2.5-32b-q4",
                        "Qwen2.5 32B",
                        "qwen2.5-32b",
                        "32.8B",
                        "Apache-2.0",
                        76,
                        26L * GIB,
                        40L * GIB,
                        "Strong dense Qwen2.5 model for complex chat, code, and multi-step tool selection.",
                        artifact(
                                "Qwen/Qwen2.5-32B-Instruct-GGUF",
                                "qwen2.5-32b-instruct-q4_k_m-00001-of-00005.gguf",
                                3_961_498_272L,
                                "403434e5c845452c013661d586b97d5a53cf207462d180a07c301eafa9390d05"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-32B-Instruct-GGUF",
                                "qwen2.5-32b-instruct-q4_k_m-00002-of-00005.gguf",
                                3_948_996_064L,
                                "7371e5c5d717a8c20f526dbfce5d3f201dc3f02140d1c836314cd0756e02e8d7"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-32B-Instruct-GGUF",
                                "qwen2.5-32b-instruct-q4_k_m-00003-of-00005.gguf",
                                3_993_478_688L,
                                "f023ccc294c3bd3b2eac5b2dd40dea3aa4ca1d06c7460ead67c36386cd62e8fc"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-32B-Instruct-GGUF",
                                "qwen2.5-32b-instruct-q4_k_m-00004-of-00005.gguf",
                                3_950_347_744L,
                                "05fe76d941454390cd7aa0de3b342f83a1d1226a959479af85bbfae7cd93e771"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-32B-Instruct-GGUF",
                                "qwen2.5-32b-instruct-q4_k_m-00005-of-00005.gguf",
                                3_997_015_616L,
                                "8c2e8ecc686129c37821bd9f3b3e251e6ae80deadd3d3348f7e3e84492a63c24"
                        )
                ),
                qwen(
                        "qwen2.5-72b-q4",
                        "Qwen2.5 72B",
                        "qwen2.5-72b",
                        "72.7B",
                        "Qwen",
                        84,
                        52L * GIB,
                        64L * GIB,
                        "Largest official Qwen2.5 instruction GGUF; intended for high-memory workstations.",
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00001-of-00012.gguf",
                                3_986_869_120L,
                                "3531a90691b5f4edb4bdf488246d72ce0b5d40cacfab1489718a662cec386e43"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00002-of-00012.gguf",
                                3_877_864_544L,
                                "a4cfb079461591e2c23ef73953aeba4b2520a9b93afaa841f31adf4e480647ab"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00003-of-00012.gguf",
                                3_963_622_720L,
                                "c67c3641d018b7ca646e7c37b50152687d4b52ce6ab6bf170b006a756b52778d"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00004-of-00012.gguf",
                                3_941_463_040L,
                                "92f5149d685ad5c5ab0fc30f5e0725d95a746d9a8e54d8397707b97dc435cd76"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00005-of-00012.gguf",
                                3_963_688_384L,
                                "f5abb6ecd7f3ec4438a4e89ae704acd43405182a0477776d5e8aa35a71a5ecdc"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00006-of-00012.gguf",
                                3_941_463_040L,
                                "ead67a4ac2648b0f7b71fea755f910ed208f3f8b881b55c208fc3fdca67359ee"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00007-of-00012.gguf",
                                3_826_849_152L,
                                "f7b168a5fdf78695736a6441dc846b19251339750ea78bf993057b7bf499671d"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00008-of-00012.gguf",
                                3_983_901_792L,
                                "4ab253ada85c14097597546ac13cfb1426cb175d1d8da520b7a2904d0c323e32"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00009-of-00012.gguf",
                                3_921_183_968L,
                                "51661b80af773b488e807a03fc7ea31bb64630c50e3ee77fbb5e37bdd9ff9979"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00010-of-00012.gguf",
                                3_995_005_888L,
                                "fcc180433606a81e2137ba1f088defe3ad51ec9353f5d129a7209614597e16f9"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00011-of-00012.gguf",
                                3_586_683_584L,
                                "57b9437649f12062bdbed6c623c3f6adc6924a3d2f4e7aabdd3ab32883f5485d"
                        ),
                        artifact(
                                "Qwen/Qwen2.5-72B-Instruct-GGUF",
                                "qwen2.5-72b-instruct-q4_k_m-00012-of-00012.gguf",
                                1_021_870_240L,
                                "60ae16428f9edb62b60e6c8657ba9344c6fae6ef44c497845dc6a6fd002e4d5b"
                        )
                ),
                qwen(
                        "qwen2.5-coder-0.5b-q4",
                        "Qwen2.5 Coder 0.5B",
                        "qwen2.5-coder-0.5b",
                        "0.49B",
                        "Apache-2.0",
                        14,
                        2L * GIB,
                        4L * GIB,
                        "Tiny code-specialized model for basic completion and low-storage testing.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
                                "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
                                491_400_064L,
                                "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32"
                        )
                ),
                qwen(
                        "qwen2.5-coder-1.5b-q4",
                        "Qwen2.5 Coder 1.5B",
                        "qwen2.5-coder-1.5b",
                        "1.54B",
                        "Apache-2.0",
                        25,
                        3L * GIB,
                        6L * GIB,
                        "Compact code-specialized model for simple source and structured tool tasks.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
                                "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                                1_117_320_768L,
                                "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046"
                        )
                ),
                qwen(
                        "qwen2.5-coder-3b-q4",
                        "Qwen2.5 Coder 3B",
                        "qwen2.5-coder-3b",
                        "3.09B",
                        "Qwen Research",
                        40,
                        5L * GIB,
                        8L * GIB,
                        "Balanced compact coding model for file work, command generation, and tool selection.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
                                "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                                2_104_932_800L,
                                "724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7"
                        )
                ),
                qwen(
                        "qwen2.5-coder-7b-q4",
                        "Qwen2.5 Coder 7B",
                        "qwen2.5-coder-7b",
                        "7.61B",
                        "Apache-2.0",
                        60,
                        8L * GIB,
                        12L * GIB,
                        "Code-specialized Qwen2.5 model with native llama.cpp tool-format support.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
                                "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                                4_683_073_536L,
                                "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c"
                        )
                ),
                qwen(
                        "qwen2.5-coder-14b-q4",
                        "Qwen2.5 Coder 14B",
                        "qwen2.5-coder-14b",
                        "14.7B",
                        "Apache-2.0",
                        72,
                        14L * GIB,
                        20L * GIB,
                        "Larger code-specialized Qwen2.5 model for technical reasoning and multi-file work.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                                "qwen2.5-coder-14b-instruct-q4_k_m.gguf",
                                8_988_110_272L,
                                "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940"
                        )
                ),
                qwen(
                        "qwen2.5-coder-32b-q4",
                        "Qwen2.5 Coder 32B",
                        "qwen2.5-coder-32b",
                        "32.8B",
                        "Apache-2.0",
                        84,
                        26L * GIB,
                        40L * GIB,
                        "High-quality dense coding model for complex technical and model-tool workflows.",
                        artifact(
                                "Qwen/Qwen2.5-Coder-32B-Instruct-GGUF",
                                "qwen2.5-coder-32b-instruct-q4_k_m.gguf",
                                19_851_335_872L,
                                "4d64b316b5e6319d9613e0d97935d9ebd631fc7e334da400d00085eca749d085"
                        )
                ),
                qwen(
                        "qwq-32b-q4",
                        "QwQ 32B",
                        "qwq-32b",
                        "32.8B",
                        "Apache-2.0",
                        88,
                        26L * GIB,
                        40L * GIB,
                        "Qwen reasoning model with official function-calling support; slower responses are expected.",
                        artifact(
                                "Qwen/QwQ-32B-GGUF",
                                "qwq-32b-q4_k_m.gguf",
                                19_851_335_840L,
                                "524a6c9b91ec47b0b1279f6e06884111c74e822c56c919cfd7769227abed93cd"
                        )
                ),
                qwen(
                        "qwen3-0.6b-q8",
                        "Qwen3 0.6B",
                        "qwen3-0.6b",
                        "0.6B",
                        "Q8_0",
                        "Apache-2.0",
                        24,
                        2L * GIB,
                        4L * GIB,
                        "Tiny Qwen3 choice for chat and basic tools; limited multi-step reliability.",
                        artifact(
                                "Qwen/Qwen3-0.6B-GGUF",
                                "Qwen3-0.6B-Q8_0.gguf",
                                639_446_688L,
                                "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
                        )
                ),
                qwen(
                        "qwen3-1.7b-q8",
                        "Qwen3 1.7B",
                        "qwen3-1.7b",
                        "1.7B",
                        "Q8_0",
                        "Apache-2.0",
                        38,
                        4L * GIB,
                        6L * GIB,
                        "Small Qwen3 choice with stronger instruction and tool behavior than sub-billion models.",
                        artifact(
                                "Qwen/Qwen3-1.7B-GGUF",
                                "Qwen3-1.7B-Q8_0.gguf",
                                1_834_426_016L,
                                "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a"
                        )
                ),
                qwen(
                        "qwen3-4b-q4",
                        "Qwen3 4B",
                        "qwen3-4b",
                        "4.0B",
                        "Q4_K_M",
                        "Apache-2.0",
                        58,
                        5L * GIB,
                        8L * GIB,
                        "Modern compact choice with explicit reasoning and agent/tool support.",
                        artifact(
                                "Qwen/Qwen3-4B-GGUF",
                                "Qwen3-4B-Q4_K_M.gguf",
                                2_497_280_256L,
                                "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5"
                        )
                ),
                qwen(
                        "qwen3-8b-q4",
                        "Qwen3 8B",
                        "qwen3-8b",
                        "8.2B",
                        "Q4_K_M",
                        "Apache-2.0",
                        69,
                        8L * GIB,
                        12L * GIB,
                        "Stronger general reasoning and tool selection with a moderate local footprint.",
                        artifact(
                                "Qwen/Qwen3-8B-GGUF",
                                "Qwen3-8B-Q4_K_M.gguf",
                                5_027_783_488L,
                                "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785"
                        )
                ),
                qwen(
                        "qwen3-14b-q4",
                        "Qwen3 14B",
                        "qwen3-14b",
                        "14.8B",
                        "Q4_K_M",
                        "Apache-2.0",
                        78,
                        14L * GIB,
                        20L * GIB,
                        "High-quality dense option for complex chat, code, and multi-step tools.",
                        artifact(
                                "Qwen/Qwen3-14B-GGUF",
                                "Qwen3-14B-Q4_K_M.gguf",
                                9_001_752_960L,
                                "500a8806e85ee9c83f3ae08420295592451379b4f8cf2d0f41c15dffeb6b81f0"
                        )
                ),
                qwen(
                        "qwen3-30b-a3b-q4",
                        "Qwen3 30B-A3B",
                        "qwen3-30b-a3b",
                        "30.5B / 3.3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        84,
                        24L * GIB,
                        32L * GIB,
                        "Mixture-of-experts option: large storage and memory footprint with about 3.3B active parameters.",
                        artifact(
                                "Qwen/Qwen3-30B-A3B-GGUF",
                                "Qwen3-30B-A3B-Q4_K_M.gguf",
                                18_556_685_824L,
                                "0d003f6662faee786ed5da3e31b29c978de5ae5d275c8794c606a7f3c01aa8f5"
                        )
                ),
                qwen(
                        "qwen3-32b-q4",
                        "Qwen3 32B",
                        "qwen3-32b",
                        "32.8B",
                        "Q4_K_M",
                        "Apache-2.0",
                        88,
                        26L * GIB,
                        40L * GIB,
                        "Largest built-in dense Qwen option; intended for high-memory systems.",
                        artifact(
                                "Qwen/Qwen3-32B-GGUF",
                                "Qwen3-32B-Q4_K_M.gguf",
                                19_762_149_024L,
                                "efd971561896866f0e910cce52761ca77b1b138090c7f15fe284676d57d1f689"
                        )
                ),
                qwen(
                        "qwen3-235b-a22b-q4",
                        "Qwen3 235B-A22B",
                        "qwen3-235b-a22b",
                        "235B / 22B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        94,
                        152L * GIB,
                        192L * GIB,
                        "Largest original Qwen3 mixture-of-experts GGUF. It requires workstation/server-class memory and storage.",
                        remoteArtifact(
                                "Qwen/Qwen3-235B-A22B-GGUF",
                                "Q4_K_M/Qwen3-235B-A22B-Q4_K_M-00001-of-00005.gguf",
                                29_742_283_872L,
                                "ce666f562eb8eefda48eec0aa93680ba40ca5727d0ba9918f3fb1771141375e6"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-235B-A22B-GGUF",
                                "Q4_K_M/Qwen3-235B-A22B-Q4_K_M-00002-of-00005.gguf",
                                29_974_106_496L,
                                "ac73c457aac5993d02444063c976062c70be609c03ca6584d0c3be0632e79ce6"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-235B-A22B-GGUF",
                                "Q4_K_M/Qwen3-235B-A22B-Q4_K_M-00003-of-00005.gguf",
                                29_933_980_640L,
                                "6357074e14a748446c2b0dcc64a1a7c64b1c124fbfb9cb48114147645d3310fe"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-235B-A22B-GGUF",
                                "Q4_K_M/Qwen3-235B-A22B-Q4_K_M-00004-of-00005.gguf",
                                29_936_094_304L,
                                "2eb47718eaeeebee3726cd2ca4ac11b5768faded5d7adddec5b754aeb7c387dd"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-235B-A22B-GGUF",
                                "Q4_K_M/Qwen3-235B-A22B-Q4_K_M-00005-of-00005.gguf",
                                22_567_608_864L,
                                "dbe2c7547c0d397d2ef16e08218a586383771dc12e9678cfd6fa8cc23e94e8f8"
                        )
                ),
                qwen(
                        "qwen3-4b-instruct-2507-q8",
                        "Qwen3 4B Instruct 2507",
                        "qwen3-4b-instruct-2507",
                        "4.0B",
                        "Q8_0",
                        "Apache-2.0",
                        64,
                        6L * GIB,
                        10L * GIB,
                        "Updated non-thinking Qwen3 4B model using a verified llama.cpp-maintainer GGUF conversion.",
                        artifact(
                                "ggml-org/Qwen3-4B-Instruct-2507-Q8_0-GGUF",
                                "qwen3-4b-instruct-2507-q8_0.gguf",
                                4_280_403_520L,
                                "ae916ede1c010a26955ee8ae2e908bf8815a3f135ec860439ab924701c69d5f1"
                        )
                ),
                qwen(
                        "qwen3-4b-thinking-2507-q8",
                        "Qwen3 4B Thinking 2507",
                        "qwen3-4b-thinking-2507",
                        "4.0B",
                        "Q8_0",
                        "Apache-2.0",
                        72,
                        6L * GIB,
                        10L * GIB,
                        "Updated thinking-focused Qwen3 4B model using a verified llama.cpp-maintainer GGUF conversion.",
                        artifact(
                                "ggml-org/Qwen3-4B-Thinking-2507-Q8_0-GGUF",
                                "qwen3-4b-thinking-2507-q8_0.gguf",
                                4_280_404_928L,
                                "b29e43d7e974e6fb270192d06ded686725d5de3da0e8b814c514e0fad0d51ab2"
                        )
                ),
                qwen(
                        "qwen3-30b-a3b-instruct-2507-q8",
                        "Qwen3 30B-A3B Instruct 2507",
                        "qwen3-30b-a3b-instruct-2507",
                        "30.5B / 3.3B active",
                        "Q8_0",
                        "Apache-2.0",
                        88,
                        38L * GIB,
                        48L * GIB,
                        "Updated non-thinking Qwen3 MoE model using a verified llama.cpp-maintainer GGUF conversion.",
                        artifact(
                                "ggml-org/Qwen3-30B-A3B-Instruct-2507-Q8_0-GGUF",
                                "qwen3-30b-a3b-instruct-2507-q8_0.gguf",
                                32_483_930_432L,
                                "66cbfc7624628ca4725a6a0581b95f2155aa6aa073a6ed4ff8d158794ee8fee3"
                        )
                ),
                qwen(
                        "qwen3-30b-a3b-thinking-2507-q8",
                        "Qwen3 30B-A3B Thinking 2507",
                        "qwen3-30b-a3b-thinking-2507",
                        "30.5B / 3.3B active",
                        "Q8_0",
                        "Apache-2.0",
                        92,
                        38L * GIB,
                        48L * GIB,
                        "Updated thinking-focused Qwen3 MoE model using a verified llama.cpp-maintainer GGUF conversion.",
                        artifact(
                                "ggml-org/Qwen3-30B-A3B-Thinking-2507-Q8_0-GGUF",
                                "qwen3-30b-a3b-thinking-2507-q8_0.gguf",
                                32_483_931_872L,
                                "e7d94445080f51ae2edb078c81c2d00d2ca6cefbc5bf7fa770782658c94f06fa"
                        )
                ),
                qwen(
                        "qwen3-coder-30b-a3b-q8",
                        "Qwen3 Coder 30B-A3B",
                        "qwen3-coder-30b-a3b",
                        "30.5B / 3.3B active",
                        "Q8_0",
                        "Apache-2.0",
                        90,
                        38L * GIB,
                        48L * GIB,
                        "Code-specialized Qwen3 MoE model using a verified llama.cpp-maintainer GGUF conversion.",
                        artifact(
                                "ggml-org/Qwen3-Coder-30B-A3B-Instruct-Q8_0-GGUF",
                                "qwen3-coder-30b-a3b-instruct-q8_0.gguf",
                                32_483_933_856L,
                                "f22993e29318b5b9ec2026f6b65802a5ca99b38ab4844aab83aed8a26ce00ff6"
                        )
                ),
                qwen(
                        "qwen3-next-80b-a3b-instruct-q4",
                        "Qwen3 Next 80B-A3B Instruct",
                        "qwen3-next-80b-a3b-instruct",
                        "80B / 3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        91,
                        54L * GIB,
                        68L * GIB,
                        "Large hybrid Qwen3 Next instruction model. The current pinned llama.cpp runtime supports this architecture.",
                        artifact(
                                "Qwen/Qwen3-Next-80B-A3B-Instruct-GGUF",
                                "Qwen3-Next-80B-A3B-Instruct-Q4_K_M.gguf",
                                48_410_988_384L,
                                "d103b2733ec1012a52d01edda66b7e5c24ae50508c9f99f5297ea459ef3c061a"
                        )
                ),
                qwen(
                        "qwen3-next-80b-a3b-thinking-q4",
                        "Qwen3 Next 80B-A3B Thinking",
                        "qwen3-next-80b-a3b-thinking",
                        "80B / 3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        94,
                        54L * GIB,
                        68L * GIB,
                        "Large hybrid Qwen3 Next thinking model. Expect substantial storage and longer reasoning responses.",
                        artifact(
                                "Qwen/Qwen3-Next-80B-A3B-Thinking-GGUF",
                                "Qwen3-Next-80B-A3B-Thinking-Q4_K_M.gguf",
                                48_410_989_824L,
                                "7fcd39df10cab070eb7d868cc48ccb274f421158e365452f01be3597b8df8d27"
                        )
                ),
                qwen(
                        "qwen3-coder-next-q4",
                        "Qwen3 Coder Next",
                        "qwen3-coder-next",
                        "80B / 3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        93,
                        54L * GIB,
                        68L * GIB,
                        "Large code-specialized Qwen3 Next model with official GGUF artifacts.",
                        remoteArtifact(
                                "Qwen/Qwen3-Coder-Next-GGUF",
                                "Qwen3-Coder-Next-Q4_K_M/Qwen3-Coder-Next-Q4_K_M-00001-of-00004.gguf",
                                15_524_827_040L,
                                "6bcfc9f9c37901eeb92172e2ab871224dab36a453d263bcb2547f737409534da"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-Coder-Next-GGUF",
                                "Qwen3-Coder-Next-Q4_K_M/Qwen3-Coder-Next-Q4_K_M-00002-of-00004.gguf",
                                14_872_168_352L,
                                "817def0691ee9d08bf3dc4444be7aed29c9e52091e8fa9d97901ce7e7f6f01d3"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-Coder-Next-GGUF",
                                "Qwen3-Coder-Next-Q4_K_M/Qwen3-Coder-Next-Q4_K_M-00003-of-00004.gguf",
                                14_503_294_496L,
                                "23aa634d47dca9b4ca3ea249384e6f01951b24c83cdc076f37f6f43d6c99883f"
                        ),
                        remoteArtifact(
                                "Qwen/Qwen3-Coder-Next-GGUF",
                                "Qwen3-Coder-Next-Q4_K_M/Qwen3-Coder-Next-Q4_K_M-00004-of-00004.gguf",
                                3_510_702_144L,
                                "249c768cc5f130dc731567d6edcbdacc48e14dec9e02c5dbe2b2185d2c5bdb2b"
                        )
                ),
                qwen(
                        "qwen3.5-0.8b-q4",
                        "Qwen3.5 0.8B",
                        "qwen3.5-0.8b",
                        "0.8B",
                        "Q4_K_M",
                        "Apache-2.0",
                        18,
                        2L * GIB,
                        4L * GIB,
                        "Smallest Qwen3.5 text-capable choice for basic chat and compatibility testing.",
                        artifact(
                                "unsloth/Qwen3.5-0.8B-GGUF",
                                "Qwen3.5-0.8B-Q4_K_M.gguf",
                                532_517_120L,
                                "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
                        )
                ),
                qwen(
                        "qwen3.5-2b-q4",
                        "Qwen3.5 2B",
                        "qwen3.5-2b",
                        "2B",
                        "Q4_K_M",
                        "Apache-2.0",
                        36,
                        3L * GIB,
                        6L * GIB,
                        "Compact Qwen3.5 model for general chat and basic structured tool selection.",
                        artifact(
                                "unsloth/Qwen3.5-2B-GGUF",
                                "Qwen3.5-2B-Q4_K_M.gguf",
                                1_280_835_840L,
                                "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
                        )
                ),
                qwen(
                        "qwen3.5-4b-q4",
                        "Qwen3.5 4B",
                        "qwen3.5-4b",
                        "4B",
                        "Q4_K_M",
                        "Apache-2.0",
                        55,
                        5L * GIB,
                        8L * GIB,
                        "Modern compact Qwen3.5 model with stronger instruction and agent behavior.",
                        artifact(
                                "unsloth/Qwen3.5-4B-GGUF",
                                "Qwen3.5-4B-Q4_K_M.gguf",
                                2_740_937_888L,
                                "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"
                        )
                ),
                qwen(
                        "qwen3.5-9b-q4",
                        "Qwen3.5 9B",
                        "qwen3.5-9b",
                        "9B",
                        "Q4_K_M",
                        "Apache-2.0",
                        75,
                        8L * GIB,
                        12L * GIB,
                        "Balanced Qwen3.5 choice for stronger chat, coding, and multi-step tool use.",
                        artifact(
                                "unsloth/Qwen3.5-9B-GGUF",
                                "Qwen3.5-9B-Q4_K_M.gguf",
                                5_680_522_464L,
                                "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
                        )
                ),
                qwen(
                        "qwen3.5-27b-q4",
                        "Qwen3.5 27B",
                        "qwen3.5-27b",
                        "27B",
                        "Q4_K_M",
                        "Apache-2.0",
                        90,
                        20L * GIB,
                        28L * GIB,
                        "Large dense Qwen3.5 model for complex reasoning, coding, and agent workflows.",
                        artifact(
                                "unsloth/Qwen3.5-27B-GGUF",
                                "Qwen3.5-27B-Q4_K_M.gguf",
                                16_740_812_704L,
                                "84b5f7f112156d63836a01a69dc3f11a6ba63b10a23b8ca7a7efaf52d5a2d806"
                        )
                ),
                qwen(
                        "qwen3.5-35b-a3b-q4",
                        "Qwen3.5 35B-A3B",
                        "qwen3.5-35b-a3b",
                        "35B / 3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        92,
                        26L * GIB,
                        36L * GIB,
                        "Qwen3.5 mixture-of-experts model with strong agent behavior and roughly 3B active parameters.",
                        artifact(
                                "unsloth/Qwen3.5-35B-A3B-GGUF",
                                "Qwen3.5-35B-A3B-Q4_K_M.gguf",
                                22_016_023_168L,
                                "3b46d1066bc91cc2d613e3bc22ce691dd77e6f0d33c9060690d24ce6de494375"
                        )
                ),
                qwen(
                        "qwen3.5-122b-a10b-q4",
                        "Qwen3.5 122B-A10B",
                        "qwen3.5-122b-a10b",
                        "122B / 10B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        95,
                        84L * GIB,
                        96L * GIB,
                        "Large Qwen3.5 mixture-of-experts model for high-memory workstations and servers.",
                        remoteArtifact(
                                "unsloth/Qwen3.5-122B-A10B-GGUF",
                                "Q4_K_M/Qwen3.5-122B-A10B-Q4_K_M-00001-of-00003.gguf",
                                10_943_552L,
                                "467c9bd92ea518539cf75bf5a5fbfbd35e9a0b40d766ccaa67bf120e12041df3"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-122B-A10B-GGUF",
                                "Q4_K_M/Qwen3.5-122B-A10B-Q4_K_M-00002-of-00003.gguf",
                                49_968_146_912L,
                                "90db14846413aebdac365b57206441437cac5f7e5037d94b325f0167f902e6e7"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-122B-A10B-GGUF",
                                "Q4_K_M/Qwen3.5-122B-A10B-Q4_K_M-00003-of-00003.gguf",
                                26_557_874_144L,
                                "e3c24b8ebec070bb4f69ea0aca25a16531da7440cd515529953e046882901f97"
                        )
                ),
                qwen(
                        "qwen3.5-397b-a17b-q4",
                        "Qwen3.5 397B-A17B",
                        "qwen3.5-397b-a17b",
                        "397B / 17B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        98,
                        260L * GIB,
                        320L * GIB,
                        "Flagship Qwen3.5 mixture-of-experts checkpoint for server-class memory and storage.",
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00001-of-00006.gguf",
                                10_943_552L,
                                "63c290c9be83e1b4dd41833d81bd933afd535d65657579b9f92f5c3f76e0218d"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00002-of-00006.gguf",
                                49_136_153_056L,
                                "dc94995a3605f3130700e96df51ee56cf93bd9340fe891918403450556453ed7"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00003-of-00006.gguf",
                                49_735_103_552L,
                                "2952dadb60137f413d5f70f6ca3c06007e24198e712c882a094432f58f76c230"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00004-of-00006.gguf",
                                49_914_377_024L,
                                "c7b99959e8fb78c8cfc9b71f3da07a2b4a6d39bf377dfa226f0a7b730c8cf3ba"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00005-of-00006.gguf",
                                49_654_346_688L,
                                "eeea4540f7289ab3baad2b3f2b4b6798e70a1802b9b4b269799a1f04d75b0af0"
                        ),
                        remoteArtifact(
                                "unsloth/Qwen3.5-397B-A17B-GGUF",
                                "Q4_K_M/Qwen3.5-397B-A17B-Q4_K_M-00006-of-00006.gguf",
                                45_642_707_040L,
                                "d3bf93bb9fe007910ae9c0fd130d7776d7c6149635c9e7f158312308beb9b754"
                        )
                ),
                qwen(
                        "qwen3.6-27b-q4",
                        "Qwen3.6 27B",
                        "qwen3.6-27b",
                        "27B",
                        "Q4_K_M",
                        "Apache-2.0",
                        94,
                        20L * GIB,
                        28L * GIB,
                        "Updated dense Qwen3.6 model for complex chat, coding, and agent workflows.",
                        artifact(
                                "unsloth/Qwen3.6-27B-GGUF",
                                "Qwen3.6-27B-Q4_K_M.gguf",
                                16_817_244_384L,
                                "5ed60d0af4650a854b1755bd392f9aef4872643dc25a254bc68043fa638392a0"
                        )
                ),
                qwen(
                        "qwen3.6-35b-a3b-q4",
                        "Qwen3.6 35B-A3B",
                        "qwen3.6-35b-a3b",
                        "35B / 3B active",
                        "Q4_K_M",
                        "Apache-2.0",
                        95,
                        26L * GIB,
                        36L * GIB,
                        "Updated Qwen3.6 mixture-of-experts model focused on agentic coding with roughly 3B active parameters.",
                        artifact(
                                "unsloth/Qwen3.6-35B-A3B-GGUF",
                                "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf",
                                22_134_528_992L,
                                "ac0e2c1189e055faa36eff361580e79c5bd6f8e76bffb4ce547f167d53e31a61"
                        )
                ),
                localTextModel(
                        "granite-4.1-3b-q4",
                        "Granite 4.1 3B",
                        "granite-4.1-3b",
                        "3B",
                        "Q4_K_M",
                        "Apache-2.0",
                        68,
                        4L * GIB,
                        8L * GIB,
                        "Official IBM compact instruct model with native llama.cpp tool-calling support.",
                        artifact(
                                "ibm-granite/granite-4.1-3b-GGUF",
                                "granite-4.1-3b-Q4_K_M.gguf",
                                2_099_501_664L,
                                "662b0626cd58f443baea23559b469df6576a81d349649c59413b36a9fb32eb29"
                        )
                ),
                localTextModel(
                        "granite-4.1-8b-q4",
                        "Granite 4.1 8B",
                        "granite-4.1-8b",
                        "8B",
                        "Q4_K_M",
                        "Apache-2.0",
                        82,
                        8L * GIB,
                        14L * GIB,
                        "Official IBM mid-size instruct model with the same Granite tool protocol and substantially stronger reasoning than the 3B tier.",
                        artifact(
                                "ibm-granite/granite-4.1-8b-GGUF",
                                "granite-4.1-8b-Q4_K_M.gguf",
                                5_347_914_400L,
                                "ed902ac9eb6adce5a90c6a08c8ea201b50e23fdc5976d1cd0362006afac5309e"
                        )
                ),
                localTextModel(
                        "granite-4.1-30b-q4",
                        "Granite 4.1 30B",
                        "granite-4.1-30b",
                        "30B",
                        "Q4_K_M",
                        "Apache-2.0",
                        94,
                        24L * GIB,
                        40L * GIB,
                        "Official IBM workstation-tier instruct model with strong reasoning and native Granite tool behavior; CPU-only execution may be slow.",
                        artifact(
                                "ibm-granite/granite-4.1-30b-GGUF",
                                "granite-4.1-30b-Q4_K_M.gguf",
                                17_490_240_736L,
                                "b33e4376e3581d11236ea53ced6b38399f6e91c0a391488486dc0827972f23f6"
                        )
                ),
                localTextModel(
                        "ministral-3-3b-instruct-2512-q4",
                        "Ministral 3 3B Instruct 2512",
                        "ministral-3-3b-instruct-2512",
                        "3B",
                        "Q4_K_M",
                        "Apache-2.0",
                        74,
                        4L * GIB,
                        8L * GIB,
                        "Official Mistral compact instruct model designed for edge deployment, structured output, and agentic tool workflows.",
                        artifact(
                                "mistralai/Ministral-3-3B-Instruct-2512-GGUF",
                                "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
                                2_147_023_008L,
                                "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8"
                        )
                ),
                localTextModel(
                        "ministral-3-8b-instruct-2512-q4",
                        "Ministral 3 8B Instruct 2512",
                        "ministral-3-8b-instruct-2512",
                        "8B",
                        "Q4_K_M",
                        "Apache-2.0",
                        86,
                        8L * GIB,
                        14L * GIB,
                        "Official balanced Mistral model for stronger chat, coding, structured output, and multi-step local automation.",
                        artifact(
                                "mistralai/Ministral-3-8B-Instruct-2512-GGUF",
                                "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
                                5_198_911_904L,
                                "33e7a72cf5e6e2cfc2f2847075acc013d68bba023e35310cef86b5cf8fdca761"
                        )
                ),
                localTextModel(
                        "ministral-3-14b-instruct-2512-q4",
                        "Ministral 3 14B Instruct 2512",
                        "ministral-3-14b-instruct-2512",
                        "14B",
                        "Q4_K_M",
                        "Apache-2.0",
                        92,
                        14L * GIB,
                        22L * GIB,
                        "Official higher-capability Mistral model for complex reasoning, code, structured output, and longer automation plans.",
                        artifact(
                                "mistralai/Ministral-3-14B-Instruct-2512-GGUF",
                                "Ministral-3-14B-Instruct-2512-Q4_K_M.gguf",
                                8_239_593_024L,
                                "824e0f3373e69b84f2cae46fdcb9bd1ebc6ab3bfc7acc125d818b7b8178cc613"
                        )
                ),
                localTextModel(
                        "smollm3-3b-q4",
                        "SmolLM3 3B",
                        "smollm3-3b",
                        "3B",
                        "Q4_K_M",
                        "Apache-2.0",
                        66,
                        4L * GIB,
                        8L * GIB,
                        "Compact Hugging Face model with a llama.cpp-maintained GGUF and a repaired embedded chat template for efficient local reasoning.",
                        artifact(
                                "ggml-org/SmolLM3-3B-GGUF",
                                "SmolLM3-Q4_K_M.gguf",
                                1_915_305_312L,
                                "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e"
                        )
                ),
                localTextModel(
                        "lfm2-2.6b-q4",
                        "LFM2 2.6B",
                        "lfm2-2.6b",
                        "2.6B",
                        "Q4_K_M",
                        "LFM1.0",
                        64,
                        4L * GIB,
                        8L * GIB,
                        "Liquid AI hybrid edge model optimized for speed and memory efficiency with native support in the pinned llama.cpp architecture set.",
                        artifact(
                                "LiquidAI/LFM2-2.6B-GGUF",
                                "LFM2-2.6B-Q4_K_M.gguf",
                                1_563_668_704L,
                                "384bc877b6c37064982f96885bef69e4475919f5969218ed4e3b9399ae0340df"
                        )
                ),
                localTextModel(
                        "gpt-oss-20b-mxfp4",
                        "GPT-OSS 20B",
                        "gpt-oss-20b",
                        "20.9B / 3.6B active",
                        "MXFP4",
                        "Apache-2.0",
                        87,
                        16L * GIB,
                        24L * GIB,
                        "Strong non-Qwen open model explicitly supported by llama.cpp in its native MXFP4 format.",
                        artifact(
                                "ggml-org/gpt-oss-20b-GGUF",
                                "gpt-oss-20b-MXFP4.gguf",
                                12_109_566_624L,
                                "27cd6c432c7672cb812a92f611cf3ba7bbc35928262bb1e1253ff4ee6ae35901"
                        )
                ),
                localTextModel(
                        "gpt-oss-120b-mxfp4",
                        "GPT-OSS 120B",
                        "gpt-oss-120b",
                        "117B / 5.1B active",
                        "MXFP4",
                        "Apache-2.0",
                        96,
                        68L * GIB,
                        80L * GIB,
                        "Very large non-Qwen open model explicitly supported by llama.cpp; intended for high-memory systems.",
                        artifact(
                                "ggml-org/gpt-oss-120b-GGUF",
                                "gpt-oss-120b-MXFP4.gguf",
                                63_387_346_208L,
                                "582bd40f6886200101f4c4ed9f25f3fe80cc14c86e9e2b37746cd8904a0c622d"
                        )
                )
        );

        private LocalModelCatalog() {
        }

        public static List<LocalModelCatalogEntry> entries() {
                return ENTRIES;
        }

        public static Optional<LocalModelCatalogEntry> find(String id) {
                if (id == null) {
                        return Optional.empty();
                }
                return ENTRIES.stream().filter(entry -> entry.id().equals(id.trim())).findFirst();
        }

        private static LocalModelCatalogEntry qwen(
                String id,
                String displayName,
                String modelId,
                String parameters,
                String license,
                int reasoningEstimate,
                long minimumMemory,
                long recommendedMemory,
                String summary,
                ModelArtifact... artifacts
        ) {
                return qwen(
                        id,
                        displayName,
                        modelId,
                        parameters,
                        "Q4_K_M",
                        license,
                        reasoningEstimate,
                        minimumMemory,
                        recommendedMemory,
                        summary,
                        artifacts
                );
        }

        private static LocalModelCatalogEntry qwen(
                String id,
                String displayName,
                String modelId,
                String parameters,
                String quantization,
                String license,
                int reasoningEstimate,
                long minimumMemory,
                long recommendedMemory,
                String summary,
                ModelArtifact... artifacts
        ) {
                return localTextModel(
                        id,
                        displayName,
                        modelId,
                        parameters,
                        quantization,
                        license,
                        reasoningEstimate,
                        minimumMemory,
                        recommendedMemory,
                        summary,
                        artifacts
                );
        }

        private static LocalModelCatalogEntry localTextModel(
                String id,
                String displayName,
                String modelId,
                String parameters,
                String quantization,
                String license,
                int reasoningEstimate,
                long minimumMemory,
                long recommendedMemory,
                String summary,
                ModelArtifact... artifacts
        ) {
                return new LocalModelCatalogEntry(
                        id,
                        displayName,
                        PROVIDER,
                        RUNTIME,
                        modelId,
                        parameters,
                        quantization,
                        license,
                        32_768,
                        minimumMemory,
                        recommendedMemory,
                        reasoningEstimate,
                        true,
                        List.of(
                                LocalModelCapabilityTag.CHAT,
                                LocalModelCapabilityTag.AUTOMATION_TOOLS
                        ),
                        summary,
                        List.of(artifacts)
                );
        }

        private static ModelArtifact artifact(String repository, String fileName, long size, String sha256) {
                return new ModelArtifact(
                        fileName,
                        URI.create("https://huggingface.co/" + repository + "/resolve/main/" + fileName),
                        size,
                        sha256
                );
        }

        private static ModelArtifact remoteArtifact(
                String repository,
                String remotePath,
                long size,
                String sha256
        ) {
                int separator = remotePath.lastIndexOf('/');
                String fileName = separator < 0 ? remotePath : remotePath.substring(separator + 1);
                return new ModelArtifact(
                        fileName,
                        URI.create("https://huggingface.co/" + repository + "/resolve/main/" + remotePath),
                        size,
                        sha256
                );
        }
}