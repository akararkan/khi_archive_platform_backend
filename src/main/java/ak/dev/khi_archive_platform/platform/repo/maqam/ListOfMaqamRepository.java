package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ListOfMaqamRepository extends JpaRepository<ListOfMaqam, Long> {

    Optional<ListOfMaqam> findByMaqamCodeAndRemovedAtIsNull(String maqamCode);

    Optional<ListOfMaqam> findByMaqamCode(String maqamCode);

    boolean existsByMaqamCode(String maqamCode);

    Page<ListOfMaqam> findAllByRemovedAtIsNull(Pageable pageable);

    Page<ListOfMaqam> findAllByRemovedAtIsNotNull(Pageable pageable);

    /** Active records the given teacher is assigned to — used by the
     *  TEACHER landing page. */
    @Query("SELECT DISTINCT m FROM ListOfMaqam m " +
            "JOIN m.teacherVotes v " +
            "WHERE m.removedAt IS NULL AND v.teacherUserId = :teacherUserId")
    Page<ListOfMaqam> findAssignedToTeacher(@Param("teacherUserId") Long teacherUserId, Pageable pageable);

    /** Active records by free-text match on song name or producer. */
    @Query("SELECT m FROM ListOfMaqam m " +
            "WHERE m.removedAt IS NULL AND (" +
            "  LOWER(m.songName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "  LOWER(m.producer) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "  LOWER(m.maqamCode) LIKE LOWER(CONCAT('%', :q, '%'))" +
            ") ORDER BY m.createdAt DESC")
    List<ListOfMaqam> searchByText(@Param("q") String q, Pageable pageable);
}
