import { Injectable, InternalServerErrorException } from '@nestjs/common';

export interface KakaoPlace {
  id: string;
  placeName: string;
  addressName: string;
  roadAddressName: string;
  phone: string;
  categoryName: string;
  latitude: number;
  longitude: number;
  placeUrl: string;
}

export interface KakaoSearchResult {
  meta: {
    totalCount: number;
    isEnd: boolean;
  };
  documents: KakaoPlace[];
}

@Injectable()
export class KakaoPlaceSearchService {
  private readonly baseUrl = 'https://dapi.kakao.com/v2/local/search/keyword.json';
  private readonly apiKey = process.env.KAKAO_REST_API_KEY;

  async searchByKeyword(
    query: string,
    options?: { x?: number; y?: number; radius?: number; page?: number; size?: number },
  ): Promise<KakaoSearchResult> {
    const params = new URLSearchParams({ query, size: String(options?.size ?? 15) });

    if (options?.x) params.append('x', String(options.x));
    if (options?.y) params.append('y', String(options.y));
    if (options?.radius) params.append('radius', String(options.radius));
    if (options?.page) params.append('page', String(options.page));

    const url = `${this.baseUrl}?${params.toString()}`;

    let res: Response;
    try {
      res = await fetch(url, {
        headers: { Authorization: `KakaoAK ${this.apiKey}` },
      });
    } catch {
      throw new InternalServerErrorException('카카오 검색 API 호출 실패');
    }

    if (!res.ok) {
      const body = await res.text();
      throw new InternalServerErrorException(`카카오 API 오류: ${res.status} - ${body}`);
    }

    const raw = await res.json() as {
      meta: { total_count: number; is_end: boolean };
      documents: Array<{
        id: string;
        place_name: string;
        address_name: string;
        road_address_name: string;
        phone: string;
        category_name: string;
        x: string;
        y: string;
        place_url: string;
      }>;
    };

    return {
      meta: {
        totalCount: raw.meta.total_count,
        isEnd: raw.meta.is_end,
      },
      documents: raw.documents.map((d) => ({
        id: d.id ?? '',
        placeName: d.place_name ?? '',
        addressName: d.address_name ?? '',
        roadAddressName: d.road_address_name ?? '',
        phone: d.phone ?? '',
        categoryName: d.category_name ?? '',
        latitude: parseFloat(d.y),
        longitude: parseFloat(d.x),
        placeUrl: d.place_url ?? '',
      })),
    };
  }
}
