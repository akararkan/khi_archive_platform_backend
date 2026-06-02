package ak.dev.khi_archive_platform.platform.repo.maqam;

import ak.dev.khi_archive_platform.platform.model.maqam.ListOfMaqam;
import ak.dev.khi_archive_platform.platform.model.maqam.MaqamTeacherVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaqamTeacherVoteRepository extends JpaRepository<MaqamTeacherVote, Long> {

    Optional<MaqamTeacherVote> findByListOfMaqamAndTeacherUserId(ListOfMaqam listOfMaqam, Long teacherUserId);

    boolean existsByListOfMaqamAndTeacherUserId(ListOfMaqam listOfMaqam, Long teacherUserId);

    long countByListOfMaqam(ListOfMaqam listOfMaqam);

    List<MaqamTeacherVote> findAllByListOfMaqamOrderByVotedAtAsc(ListOfMaqam listOfMaqam);

    List<MaqamTeacherVote> findAllByTeacherUserId(Long teacherUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MaqamTeacherVote v SET v.totalListenSeconds = COALESCE(v.totalListenSeconds, 0) + :delta, " +
            "v.maxPositionSeconds = GREATEST(COALESCE(v.maxPositionSeconds, 0), :position), " +
            "v.lastListenAt = :now, " +
            "v.version = COALESCE(v.version, 0) + 1 " +
            "WHERE v.id = :voteId")
    int bumpListen(@Param("voteId") Long voteId,
                   @Param("delta") long deltaSeconds,
                   @Param("position") long positionSeconds,
                   @Param("now") java.time.Instant now);
}
