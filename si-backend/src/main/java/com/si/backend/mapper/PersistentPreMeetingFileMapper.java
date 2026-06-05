package com.si.backend.mapper;

import com.si.backend.entity.PersistentPreMeetingFile;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PersistentPreMeetingFileMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS pre_meeting_file_persistent (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                meeting_id   BIGINT        NOT NULL,
                file_name    VARCHAR(512)  NOT NULL,
                file_type    VARCHAR(32)   DEFAULT NULL,
                file_content MEDIUMTEXT    DEFAULT NULL,
                summary      MEDIUMTEXT    DEFAULT NULL,
                create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_pmfp_meeting_id (meeting_id),
                CONSTRAINT fk_pmfp_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTableIfNotExists();

    @Update("ALTER TABLE pre_meeting_file_persistent ADD COLUMN file_data LONGBLOB DEFAULT NULL")
    void addFileDataColumnIfNotExists();

    @Insert("INSERT INTO pre_meeting_file_persistent (meeting_id, file_name, file_type, file_content, file_data, summary, create_time) " +
            "VALUES (#{meetingId}, #{fileName}, #{fileType}, #{fileContent}, #{fileData}, #{summary}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PersistentPreMeetingFile file);

    @Select("SELECT id, meeting_id, file_name, file_type, summary, create_time FROM pre_meeting_file_persistent WHERE meeting_id = #{meetingId} ORDER BY create_time ASC")
    List<PersistentPreMeetingFile> findByMeetingId(Long meetingId);

    @Select("SELECT id, meeting_id, file_name, file_type, file_content, summary, create_time FROM pre_meeting_file_persistent WHERE id = #{id}")
    PersistentPreMeetingFile findById(Long id);

    @Select("SELECT id, meeting_id, file_name, file_type, file_data FROM pre_meeting_file_persistent WHERE id = #{id}")
    PersistentPreMeetingFile findByIdForDownload(Long id);

    @Select("SELECT id, meeting_id, file_name, file_type, file_content, file_data, summary, create_time FROM pre_meeting_file_persistent WHERE id = #{id}")
    PersistentPreMeetingFile findByIdFull(Long id);

    @Update("UPDATE pre_meeting_file_persistent SET summary = #{summary} WHERE id = #{id}")
    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    @Delete("DELETE FROM pre_meeting_file_persistent WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM pre_meeting_file_persistent WHERE meeting_id = #{meetingId}")
    int deleteByMeetingId(Long meetingId);
}
