package com.clamber.playback.config;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Id;

@Setter
@Getter
public class BaseEntity {

    /**
     * 主键。必须带 @Id：tk.mybatis 在实体没有 @Id 时会把「所有字段」当作主键条件处理，
     * selectByPrimaryKey / updateByPrimaryKey 之类的方法行为都不正确。
     */
    @Id
    private String id;
}
