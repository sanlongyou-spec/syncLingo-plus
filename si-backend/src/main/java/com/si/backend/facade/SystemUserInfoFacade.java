package com.si.backend.facade;

import com.si.backend.dto.SaveSystemUserInfoRequest;
import com.si.backend.entity.SystemUserInfo;
import com.si.backend.service.SystemUserInfoService;
import com.si.backend.vo.SystemUserImportResultVo;
import com.si.backend.vo.SystemUserInfoVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Facade for system-wide user information management.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemUserInfoFacade {

    private final SystemUserInfoService userInfoService;

    public List<SystemUserInfoVo> list(String keyword) {
        log.info("[SystemUserInfoFacade] list start, keywordLen={}", keyword != null ? keyword.length() : 0);
        List<SystemUserInfoVo> result = userInfoService.list(keyword).stream()
                .map(this::toVo)
                .toList();
        log.info("[SystemUserInfoFacade] list end, count={}", result.size());
        return result;
    }

    public SystemUserInfoVo create(SaveSystemUserInfoRequest request) {
        log.info("[SystemUserInfoFacade] create start, email={}", request != null ? request.getEmail() : null);
        SystemUserInfoVo result = toVo(userInfoService.create(toEntity(request)));
        log.info("[SystemUserInfoFacade] create end, id={}", result.getId());
        return result;
    }

    public SystemUserInfoVo update(Long id, SaveSystemUserInfoRequest request) {
        log.info("[SystemUserInfoFacade] update start, id={}", id);
        SystemUserInfoVo result = toVo(userInfoService.update(id, toEntity(request)));
        log.info("[SystemUserInfoFacade] update end, id={}", id);
        return result;
    }

    public void delete(Long id) {
        log.info("[SystemUserInfoFacade] delete start, id={}", id);
        userInfoService.delete(id);
        log.info("[SystemUserInfoFacade] delete end, id={}", id);
    }

    public SystemUserImportResultVo importExcel(MultipartFile file) {
        log.info("[SystemUserInfoFacade] importExcel start, fileName={}", file != null ? file.getOriginalFilename() : null);
        SystemUserImportResultVo result = userInfoService.importExcel(file);
        log.info("[SystemUserInfoFacade] importExcel end, total={}", result.getTotalCount());
        return result;
    }

    private SystemUserInfo toEntity(SaveSystemUserInfoRequest request) {
        SystemUserInfo userInfo = new SystemUserInfo();
        if (request == null) {
            return userInfo;
        }
        userInfo.setDepartment(request.getDepartment());
        userInfo.setPersonName(request.getPersonName());
        userInfo.setPositionTitle(request.getPositionTitle());
        userInfo.setEmail(request.getEmail());
        userInfo.setMicrosoftId(request.getMicrosoftId());
        userInfo.setRobinUid(request.getRobinUid());
        userInfo.setTeamsVerified(request.getTeamsVerified());
        userInfo.setEmploymentStatus(request.getEmploymentStatus());
        return userInfo;
    }

    private SystemUserInfoVo toVo(SystemUserInfo userInfo) {
        return SystemUserInfoVo.builder()
                .id(userInfo.getId())
                .department(userInfo.getDepartment())
                .personName(userInfo.getPersonName())
                .positionTitle(userInfo.getPositionTitle())
                .email(userInfo.getEmail())
                .microsoftId(userInfo.getMicrosoftId())
                .robinUid(userInfo.getRobinUid())
                .teamsVerified(userInfo.getTeamsVerified())
                .employmentStatus(userInfo.getEmploymentStatus())
                .sourceSheet(userInfo.getSourceSheet())
                .sourceRow(userInfo.getSourceRow())
                .createTime(userInfo.getCreateTime())
                .updateTime(userInfo.getUpdateTime())
                .build();
    }
}
