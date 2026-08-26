package com.auralink.provider.seedream;

import org.springframework.stereotype.Component;

import com.auralink.provider.qwen.PaintingPromptPlan;

/** Converts only a validated internal Qwen plan into the final Seedream prompt. */
@Component
public class PoemPlanSeedreamPromptBuilder {

    public String build(PaintingPromptPlan plan) {
        return "创作一幅中国画（国画），严格依据下方已验证的诗意画面计划。"
                + "保持计划中的主题、场景、构图、色彩、笔墨和意境；"
                + "不添加无依据的新主体、文字、标志、徽标、现代界面边框或真实艺术家署名。"
                + "\n【画面计划开始】\n"
                + plan.finalPrompt()
                + "\n【画面计划结束】";
    }
}
