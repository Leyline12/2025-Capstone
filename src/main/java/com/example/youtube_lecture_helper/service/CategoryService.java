package com.example.youtube_lecture_helper.service;

import com.example.youtube_lecture_helper.dto.CategoryResponseDto;
import com.example.youtube_lecture_helper.dto.UserVideoInfoDto;
import com.example.youtube_lecture_helper.entity.Category;
import com.example.youtube_lecture_helper.entity.User;
import com.example.youtube_lecture_helper.entity.UserVideoCategory;
import com.example.youtube_lecture_helper.entity.Video;
import com.example.youtube_lecture_helper.exception.AccessDeniedException;
import com.example.youtube_lecture_helper.exception.EntityNotFoundException;
import com.example.youtube_lecture_helper.repository.CategoryRepository;
import com.example.youtube_lecture_helper.repository.UserRepository;
import com.example.youtube_lecture_helper.repository.UserVideoCategoryRepository;
import com.example.youtube_lecture_helper.repository.VideoRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final UserVideoCategoryRepository userVideoCategoryRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    public static final Long DEFAULT_CATEGORY_ID = 1L;

    public List<CategoryResponseDto> getAllCategoryHierarchyWithVideos(Long userId) {
        // 루트 카테고리만 먼저 조회
        List<Category> rootCategories = categoryRepository.findByParentIdIsNull();

        // 모든 카테고리들의 계층 구조를 만들기
        List<CategoryResponseDto> result = new ArrayList<>();
        for (Category rootCategory : rootCategories) {
            CategoryResponseDto rootDto = buildCategoryHierarchy(rootCategory);
            // 해당 유저의 카테고리인 경우만 추가
            if (rootCategory.getUser() == null || rootCategory.getUser().getId().equals(userId)) {
                result.add(rootDto);
            }
        }

        // 모든 카테고리 ID 추출
        List<Long> allCategoryIds = getAllCategoryIds(result);

        // 비어있지 않은 경우만 조회 수행
        if (!allCategoryIds.isEmpty()) {
            // 모든 카테고리의 비디오 정보를 한 번에 조회
            List<UserVideoCategory> allVideos = userVideoCategoryRepository
                    .findByCategoryIdsWithVideo(allCategoryIds);

            // 비디오 정보를 각 카테고리 DTO에 할당
            mapVideosToCategories(result, allVideos, userId);
        }

        return result;
    }

    /**
     * 특정 카테고리와 그 하위 카테고리의 계층 구조를 재귀적으로 구성
     */
    private CategoryResponseDto buildCategoryHierarchy(Category category) {
        CategoryResponseDto dto = new CategoryResponseDto(category);

        List<Category> childCategories = categoryRepository.findByParentId(category);
        for (Category child : childCategories) {
            dto.getChildren().add(buildCategoryHierarchy(child));
        }

        return dto;
    }

    /**
     * 모든 카테고리 ID를 추출하는 헬퍼 메서드
     */
    private List<Long> getAllCategoryIds(List<CategoryResponseDto> categories) {
        List<Long> result = new ArrayList<>();
        for (CategoryResponseDto category : categories) {
            result.add(category.getId());
            result.addAll(getAllCategoryIds(category.getChildren()));
        }
        return result;
    }

    /**
     * 비디오 정보를 카테고리 DTO에 매핑하는 헬퍼 메서드
     */
    private void mapVideosToCategories(List<CategoryResponseDto> categories, List<UserVideoCategory> videos, Long userId) {
        // 카테고리 ID -> 카테고리 DTO 매핑을 위한 맵 생성
        Map<Long, CategoryResponseDto> categoryMap = new HashMap<>();
        buildCategoryMap(categories, categoryMap);

        // 비디오를 해당 카테고리에 할당
        for (UserVideoCategory video : videos) {
            // 해당 유저의 비디오만 추가
            if (video.getUser().getId().equals(userId)) {
                Long categoryId = video.getCategory().getId();
                CategoryResponseDto categoryResponseDto = categoryMap.get(categoryId);
                if (categoryResponseDto != null) {
                    categoryResponseDto.getVideos().add(new UserVideoInfoDto(video));
                }
            }
        }
    }

    /**
     * 카테고리 ID -> DTO 매핑을 위한 헬퍼 메서드
     */
    private void buildCategoryMap(List<CategoryResponseDto> categories, Map<Long, CategoryResponseDto> categoryMap) {
        for (CategoryResponseDto category : categories) {
            categoryMap.put(category.getId(), category);
            buildCategoryMap(category.getChildren(), categoryMap);
        }
    }

    /**
     * 특정 카테고리의 비디오만 조회
     */
    /**
     * 특정 카테고리의 비디오만 조회
     */
    public CategoryResponseDto getCategoryWithVideos(Long categoryId, Long userId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("카테고리를 찾을 수 없습니다: " + categoryId));

        // 해당 카테고리가 유저에게 접근 권한이 있는지 확인
        if (category.getUser() != null && !category.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("해당 카테고리에 접근할 권한이 없습니다.");
        }

        CategoryResponseDto dto = new CategoryResponseDto(category);

        // 하위 카테고리 구성 - ID만 사용하는 쿼리로 최적화
        List<Category> childCategories = categoryRepository.findByParentId(category);
        for (Category child : childCategories) {
            dto.getChildren().add(new CategoryResponseDto(child));
        }

        // 비디오 정보 추가 - 필요한 필드만 포함하는 쿼리 사용
        List<UserVideoCategory> userVideos = userVideoCategoryRepository.findByCategoryIdWithVideo(categoryId);
        for (UserVideoCategory userVideo : userVideos) {
            // 해당 유저의 비디오만 추가
            if (userVideo.getUser().getId().equals(userId)) {
                dto.getVideos().add(new UserVideoInfoDto(userVideo));
            }
        }

        return dto;
    }
    /**
     * 카테고리 추가
     */
    public CategoryResponseDto createCategory(String name, Long parentId, Long userId){
        User user = new User(userId);
        Category category = new Category();
        category.setName(name);
        category.setUser(user);

        if (parentId != null) {
            Category parent = categoryRepository.findById(parentId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent category not found: " + parentId));
            category.setParentId(parent);
        }

        return new CategoryResponseDto(categoryRepository.save(category));
    }
    /**
     * 카테고리에 비디오 추가
     */
    @Transactional
    public void addVideoToCategory(Long userId, String youtubeId, Long categoryId, String userVideoName) {
        // 비디오 확인
        Video video = videoRepository.findByYoutubeId(youtubeId)
                .orElseThrow(() -> new EntityNotFoundException("해당 유튜브 ID의 비디오를 찾을 수 없습니다: " + youtubeId));
        
        //default category일 경우(카테고리 지정 안함)
        if(categoryId != DEFAULT_CATEGORY_ID){
            // 카테고리 접근 권한 확인
            Long categoryOwnerId = categoryRepository.findOwnerIdById(categoryId)
                    .orElseThrow(() -> new EntityNotFoundException("카테고리를 찾을 수 없습니다: " + categoryId));

            if (categoryOwnerId != null && !categoryOwnerId.equals(userId)) {
                throw new AccessDeniedException("해당 카테고리에 접근할 권한이 없습니다.");
            }
        }

        // 기존 uvc 항목 확인 (userId-youtubeId로 조회)
        Optional<UserVideoCategory> existingEntry = userVideoCategoryRepository
                .findByUserIdAndVideoId(userId, video.getId());

        if (existingEntry.isPresent()) {
            // 기존 항목이 있으면 카테고리와 이름 업데이트
            UserVideoCategory uvc = existingEntry.get();

            // 새 카테고리 설정
            Category category = new Category();
            category.setId(categoryId);
            uvc.setCategory(category);

            // 이름 업데이트 (이름이 제공된 경우에만)
            if (userVideoName != null && !userVideoName.isEmpty()) {
                uvc.setUserVideoName(userVideoName);
            }

            userVideoCategoryRepository.save(uvc);
        } else {
            // 기존 항목이 없으면 새로 생성
            User user = new User();
            user.setId(userId);

            Category category = new Category();
            category.setId(categoryId);

            UserVideoCategory userVideoCategory = new UserVideoCategory(
                    user, video, category, userVideoName
            );
            userVideoCategoryRepository.save(userVideoCategory);
        }
    }

    /**
     * 카테고리에서 비디오 제거
     */
    public void removeVideoFromCategory(Long userId, String youtubeId, Long categoryId) {
        Video video = videoRepository.findByYoutubeId(youtubeId)
                .orElseThrow(() -> new EntityNotFoundException("해당 유튜브 ID의 비디오를 찾을 수 없습니다: " + youtubeId));

        Optional<UserVideoCategory> entryToRemove = userVideoCategoryRepository
                .findByUserIdCategoryIdAndVideoId(userId, categoryId, video.getId());

        if (entryToRemove.isEmpty()) {
            throw new EntityNotFoundException("해당 카테고리에 비디오가 존재하지 않습니다.");
        }

        userVideoCategoryRepository.delete(entryToRemove.get());
    }

}
