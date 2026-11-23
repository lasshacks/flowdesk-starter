package com.flowdesk.task.repository;
import com.flowdesk.task.domain.Task;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
@Transactional(readOnly=true)
public interface TaskRepository extends Repository<Task,Long> {
    Slice<Task> findByProjectIdOrderByIdAsc(Long projectId,Pageable pageable);
    Slice<Task> findByProjectIdAndArchivedAtIsNullOrderByIdAsc(Long projectId,Pageable pageable);
    Optional<Task> findByIdAndProjectId(Long id,Long projectId);
}
