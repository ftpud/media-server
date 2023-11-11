package live.jmusic.mediacoreservice.repository;

import live.jmusic.mediacoreservice.repository.model.MediaItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface MediaRepository extends JpaRepository<MediaItem, Long> {

    <S extends MediaItem> S save(S entity);

    Optional<MediaItem> findById(Long aLong);

    Optional<MediaItem> findByFullpath(String fullpath);


}
