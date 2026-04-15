package com.clamber.playback.config;

import org.apache.ibatis.annotations.SelectProvider;
import tk.mybatis.mapper.additional.update.differ.UpdateByDifferMapper;
import tk.mybatis.mapper.additional.update.force.UpdateByPrimaryKeySelectiveForceMapper;
import tk.mybatis.mapper.annotation.RegisterMapper;
import tk.mybatis.mapper.common.ConditionMapper;
import tk.mybatis.mapper.common.Mapper;
import tk.mybatis.mapper.common.MySqlMapper;

import java.util.List;

@RegisterMapper
public interface CommonMapper<T> extends Mapper<T>, MySqlMapper<T>, ConditionMapper<T>, UpdateByDifferMapper<T>
        , UpdateByPrimaryKeySelectiveForceMapper<T> {

    @SelectProvider(type = CommonProvider.class, method = "dynamicSQL")
    List<T> selectAll();


    List<T> selectCustom(String condition);


}