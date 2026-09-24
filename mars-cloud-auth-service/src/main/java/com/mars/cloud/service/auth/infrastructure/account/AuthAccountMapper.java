package com.mars.cloud.service.auth.infrastructure.account;

import org.apache.ibatis.annotations.Select;

public interface AuthAccountMapper {
    @Select("SELECT user_id FROM sys_user WHERE user_id=#{id} AND status='ACTIVE'")
    Long activeUser(long id);
    @Select("SELECT secret_hash FROM sys_user_credential WHERE user_id=#{id} AND type='LOCAL_TEST'")
    String localPassword(long id);
}
