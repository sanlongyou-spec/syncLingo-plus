package com.si.backend.mapper;

import com.si.backend.entity.SiUser;
import org.apache.ibatis.annotations.*;

/**
 * 用户 Mapper，操作 si_user 表。
 */
@Mapper
public interface UserMapper {

    @Select("SELECT * FROM si_user WHERE username = #{username}")
    SiUser findByUsername(String username);

    @Select("SELECT * FROM si_user WHERE id = #{id}")
    SiUser findById(Long id);

    @Insert("INSERT INTO si_user (username, password, nickname, email, role, create_time, update_time) " +
            "VALUES (#{username}, #{password}, #{nickname}, #{email}, #{role}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SiUser user);
}
