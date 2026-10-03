// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link Pageable} basé sur un offset absolu (pas {@code page * size}).
 * L'ordre vient de la méthode repository ({@code OrderBy…}).
 */
public record OffsetPageRequest(int offset, int size) implements Pageable {

    public OffsetPageRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("offset < 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size < 1");
        }
    }

    @Override
    public int getPageNumber() {
        return offset / size;
    }

    @Override
    public int getPageSize() {
        return size;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + size, size);
    }

    @Override
    public Pageable previousOrFirst() {
        return offset <= size ? first() : new OffsetPageRequest(offset - size, size);
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, size);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest(pageNumber * size, size);
    }
}
