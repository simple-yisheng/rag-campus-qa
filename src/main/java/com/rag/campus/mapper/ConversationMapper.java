package com.rag.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.campus.entity.Conversation;

/**
 * 对话记录 Mapper（旧表 tb_conversation，v3 起已拆分，保留兼容）
 */
@Deprecated
public interface ConversationMapper extends BaseMapper<Conversation> {
}
