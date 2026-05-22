package com.si.backend.mapper;

import com.si.backend.entity.MeetingMaterial;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for session-bound meeting agenda/report materials.
 */
@Mapper
public interface MeetingMaterialMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS meeting_material (
                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id      VARCHAR(64) NOT NULL,
                title           VARCHAR(256),
                agenda_text     LONGTEXT,
                report_text     LONGTEXT,
                executive_names VARCHAR(1024),
                summary_text    LONGTEXT,
                create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
                update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_meeting_material_session (session_id)
            )
            """)
    void createTableIfNotExists();

    @Insert("""
            INSERT INTO meeting_material
            (session_id, title, agenda_text, report_text, executive_names, summary_text, create_time, update_time)
            VALUES
            (#{sessionId}, #{title}, #{agendaText}, #{reportText}, #{executiveNames}, #{summaryText}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MeetingMaterial material);

    @Update("""
            UPDATE meeting_material
            SET title = #{title},
                agenda_text = #{agendaText},
                report_text = #{reportText},
                executive_names = #{executiveNames},
                update_time = NOW()
            WHERE session_id = #{sessionId}
            """)
    int updateMaterial(MeetingMaterial material);

    @Update("""
            UPDATE meeting_material
            SET summary_text = #{summaryText},
                update_time = NOW()
            WHERE session_id = #{sessionId}
            """)
    int updateSummary(@Param("sessionId") String sessionId, @Param("summaryText") String summaryText);

    @Select("SELECT * FROM meeting_material WHERE session_id = #{sessionId}")
    MeetingMaterial findBySessionId(String sessionId);
}
