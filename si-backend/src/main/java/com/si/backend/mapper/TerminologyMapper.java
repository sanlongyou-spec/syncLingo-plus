package com.si.backend.mapper;

import com.si.backend.entity.Terminology;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 术语 Mapper，操作 terminology 表。
 */
@Mapper
public interface TerminologyMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS terminology (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id     BIGINT DEFAULT 1,
                term_zh     VARCHAR(255),
                term_id     VARCHAR(255),
                term_en     VARCHAR(255),
                pinyin      VARCHAR(255),
                category    VARCHAR(64),
                note        VARCHAR(512),
                source_sheet VARCHAR(128),
                source_row  INT,
                review_status VARCHAR(32) DEFAULT 'APPROVED',
                enabled     TINYINT DEFAULT 1,
                create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_enabled (enabled),
                INDEX idx_category (category)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO terminology (
                user_id, term_zh, term_id, term_en, pinyin, category, note, source_sheet, source_row,
                review_status, enabled, create_time, update_time
            )
            VALUES (
                #{userId}, #{termZh}, #{termId}, #{termEn}, #{pinyin}, #{category}, #{note}, #{sourceSheet}, #{sourceRow},
                #{reviewStatus}, #{enabled}, NOW(), NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Terminology terminology);

    @Select("SELECT * FROM terminology WHERE user_id = #{userId} AND enabled = 1 ORDER BY id ASC")
    List<Terminology> findEnabled(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM terminology
            WHERE user_id = #{userId}
            AND (
                #{keyword} IS NULL OR #{keyword} = ''
                OR term_zh LIKE CONCAT('%', #{keyword}, '%')
                OR term_id LIKE CONCAT('%', #{keyword}, '%')
                OR term_en LIKE CONCAT('%', #{keyword}, '%')
                OR category LIKE CONCAT('%', #{keyword}, '%')
            )
            AND (#{enabled} IS NULL OR enabled = #{enabled})
            ORDER BY id DESC
            """)
    List<Terminology> findAll(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("enabled") Boolean enabled);

    @Select("SELECT * FROM terminology WHERE id = #{id} AND user_id = #{userId}")
    Terminology findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE terminology SET enabled = #{enabled}, update_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateEnabled(@Param("id") Long id, @Param("userId") Long userId, @Param("enabled") Boolean enabled);

    @Update("""
            UPDATE terminology
            SET term_zh = #{termZh},
                term_id = #{termId},
                term_en = #{termEn},
                pinyin = #{pinyin},
                category = #{category},
                note = #{note},
                review_status = #{reviewStatus},
                enabled = #{enabled},
                update_time = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(Terminology terminology);

    @Update("DELETE FROM terminology WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    @Update("DELETE FROM terminology WHERE user_id = #{userId}")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Update("ALTER TABLE terminology ADD COLUMN user_id BIGINT DEFAULT 1 AFTER id")
    void addUserIdColumnIfNotExists();

    @Update("UPDATE terminology SET user_id = 1 WHERE user_id IS NULL")
    int backfillDefaultUserId();

    @Update("ALTER TABLE terminology ADD COLUMN pinyin VARCHAR(255)")
    void addPinyinColumnIfNotExists();

    @Update("ALTER TABLE terminology ADD COLUMN note VARCHAR(512)")
    void addNoteColumnIfNotExists();

    @Update("ALTER TABLE terminology ADD COLUMN source_sheet VARCHAR(128)")
    void addSourceSheetColumnIfNotExists();

    @Update("ALTER TABLE terminology ADD COLUMN source_row INT")
    void addSourceRowColumnIfNotExists();

    @Update("ALTER TABLE terminology ADD COLUMN review_status VARCHAR(32) DEFAULT 'APPROVED'")
    void addReviewStatusColumnIfNotExists();
}
