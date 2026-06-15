#!/usr/bin/env python3
"""
Compare compression latency: current model (claude-haiku-4.5) vs Gemini Flash Lite.

Usage:
  export OPENAI_API_KEY=<openrouter key>
  export OPENAI_BASE_URL=https://openrouter.ai/api/v1   # or your base URL
  python3 tests/test_compression_latency.py

  # Override models:
  MODEL_A=anthropic/claude-haiku-4.5 MODEL_B=google/gemini-2.0-flash-lite python3 tests/test_compression_latency.py
"""

import os
import time
import json
import random
import statistics
import urllib.request
import urllib.error
import concurrent.futures

# ---------- Config ----------
API_KEY  = os.environ.get("OPENAI_API_KEY", "")
BASE_URL = os.environ.get("OPENAI_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/")
TARGET_RATIO = 0.60   # zh→id target ratio, same as application.yml
MAX_TOKENS   = 512
CONCURRENCY  = 4      # parallel requests per model (matches server behavior)
TIMEOUT_S    = 15  # per request; free-tier models get cut off faster

MODELS = [
    # current
    "anthropic/claude-haiku-4.5",
    # free tier
    "meta-llama/llama-3.3-70b-instruct:free",
    "openai/gpt-oss-20b:free",
    "openai/gpt-oss-120b:free",
    "google/gemma-4-26b-a4b-it:free",
    # ultra cheap
    "meta-llama/llama-3.1-8b-instruct",
    "mistralai/mistral-nemo",
    "amazon/nova-micro-v1",
    "qwen/qwen-2.5-7b-instruct",
    "google/gemma-3-4b-it",
    "google/gemma-3-12b-it",
    # low price
    "qwen/qwen3.5-flash-02-23",
    "bytedance-seed/seed-1.6-flash",
    "mistralai/mistral-small-3.2-24b-instruct",
    "deepseek/deepseek-v4-flash",
    "google/gemini-2.5-flash-lite",
    "openai/gpt-4.1-nano",
    "meta-llama/llama-3.3-70b-instruct",
]

# ---------- Compression prompt (identical to LlmIntegration.java) ----------
PROMPT_TEMPLATE = (
    "You are a simultaneous interpretation compression model.\n\n"
    "Task: Compress the already-translated Indonesian text into a concise real-time interpretation version.\n\n"
    "[Input Rules]\n"
    "- The input text is already in Indonesian. Do NOT translate again.\n"
    "- Preserve all proper nouns unchanged.\n\n"
    "[Compression Rules]\n"
    "- Remove filler words, repetitions, and overly formal connectives.\n"
    "- Simplify complex sentence structures while preserving meaning.\n"
    "- Preserve all facts, actions, entities, numbers, and results.\n"
    "- Keep the original order.\n"
    f"- Target length: keep about {int(TARGET_RATIO*100)}% of the original length when possible.\n\n"
    "Output only the compressed text, no explanation."
)

# ---------- 100 Indonesian sentences (real meeting translation style, ~150-500 chars each) ----------
# These match actual zh-CN→id simultaneous interpretation output length and style.
# Source: typical business/plantation management meeting transcripts.
SENTENCES = [
    "Katalog itu kompleks. Gulma dan rumput serius. Masalah penutup meliputi penanaman lubang melingkar, penguningan dan pengeringan batang serta daun pohon, tersebar di daerah berpasir pegunungan. Restorasi sulit. Pengabaian blok membatasi kapasitas produksi taman secara keseluruhan.",
    "Pada tahun 2021 dan 2023, total 5.893 bibit telah ditanam kembali, namun kualitasnya rendah. Kekurangan pemupukan sebelum peluncuran pasar memengaruhi beberapa bibit sisipan yang sudah memasuki fase produksi dan berdampak pada hasil panen awal.",
    "Nilai produksi rendah, tren kenaikan keseluruhan lemah dan hasil tidak merata. Kurangnya pekerjaan industri dan sanitasi menunjukkan kekurangan serius dalam pemeliharaan dan manajemen. Sanyuan telah sepenuhnya memulai upaya perbaikan dan kemajuan di seluruh area.",
    "Proyek menetapkan target menyelesaikan restorasi dasar dalam empat bulan. Perbaikan jalan dan rehabilitasi telah selesai 55%, tahap dengan kemajuan tercepat, namun beberapa area di wilayah timur masih membutuhkan perhatian lebih lanjut dari tim lapangan.",
    "Pemberantasan gulma selesai 36%, berjalan lambat karena tingkat kehadiran pekerja yang rendah dan efisiensi kerja yang kurang memadai. Pekerjaan industri selesai 24%, disebabkan ketidakstabilan tim kontraktor yang bergantian masuk dan keluar dari area kerja.",
    "Kemajuan perbaikan mencapai 61%, namun beberapa area masih terpengaruh oleh kondisi jalan dan gulma yang belum dibersihkan. Kemajuan lanjut perlu dilakukan setelah pemberantasan gulma selesai. Dari perubahan produksi, beberapa blok menunjukkan peningkatan signifikan.",
    "Satu blok menunjukkan peningkatan pohon berbuah dari 170 pohon pada bulan Maret menjadi 340 pohon pada bulan Mei melalui operasi terpusat dan terkoordinasi dengan baik. Langkah perbaikan telah membuahkan hasil nyata yang dapat terlihat dari data produksi lapangan.",
    "Fokus bergeser dari manajemen operasional ke manajemen hasil melalui sistem manajemen pemeliharaan terstandarisasi dan sistem pemantauan terpadu, dengan penekanan pada pengembangan jalan, pemberantasan gulma, dan tanam sisip, serta implementasi bertahap menuju target akhir.",
    "Tujuan mencapai tingkat restorasi dasar di seluruh wilayah dan bertahap meningkatkan kapasitas produksi dalam empat bulan, dengan tingkat keberhasilan restorasi lebih dari 90% untuk memastikan stabilitas dan keberlanjutan produksi di semua area taman.",
    "Luasnya 102,72 hektar dengan total 3048 pohon. Rata-rata kepadatan pohon relatif rendah dibandingkan standar optimal. Lahan terbengkalai dibagi dua kategori: parah seluas 55,65 hektar dengan 1717 pohon, dan ringan seluas 47,06 hektar dengan 1331 pohon.",
    "Karakteristik utama lahan terbengkalai adalah jalan yang kompleks dan tidak terawat, sedangkan gulma menutupi permukaan tanah secara merata, piringan pohon tidak tersedia, dan belum pernah dilakukan pemupukan sehingga daun-daun pohon menguning dan mengering.",
    "Setelah analisis menyeluruh, masalah mendasar bukan hanya soal tugas operasional tunggal, melainkan kombinasi kehilangan air dan pupuk yang serius akibat kondisi tanah yang buruk, ditambah pengelolaan yang tidak memadai selama bertahun-tahun tanpa pengawasan ketat.",
    "Kami melaporkan situasi dan kemajuan perbaikan material di area produksi Sanyuan. Fokus utama periode ini adalah peningkatan kualitas perawatan tanaman, pengelolaan air irigasi, dan perbaikan infrastruktur jalan yang mempengaruhi efisiensi operasional secara keseluruhan.",
    "Selamat malam semuanya. Saya akan melaporkan situasi terkini dan kemajuan perbaikan di area produksi. Poin pertama adalah gambaran umum kondisi lapangan saat ini, termasuk tantangan yang dihadapi dan langkah-langkah konkret yang telah dan akan segera diambil.",
    "Berdasarkan evaluasi komprehensif yang dilakukan tim teknis selama dua minggu terakhir, kami mengidentifikasi bahwa produktivitas lahan berada di bawah standar akibat kombinasi masalah agronomis dan manajemen yang perlu ditangani secara simultan dan terkoordinasi.",
    "Anggaran operasional kuartal ini telah dialokasikan untuk tiga prioritas utama: perbaikan infrastruktur jalan sebesar 40%, program pemupukan menyeluruh sebesar 35%, dan rekrutmen serta pelatihan tenaga kerja lapangan sebesar 25% dari total anggaran yang tersedia.",
    "Tim pengawasan lapangan melaporkan bahwa tingkat kehadiran pekerja harian rata-rata hanya mencapai 68% dari kapasitas yang dibutuhkan, sehingga target penyelesaian pekerjaan pemberantasan gulma dan pemeliharaan rutin mengalami keterlambatan yang signifikan.",
    "Program penyesuaian harga upah yang telah diterapkan sejak bulan lalu mulai menunjukkan hasil positif dalam hal stabilisasi tenaga kerja. Tim kontraktor yang sebelumnya tidak stabil kini mulai menunjukkan komitmen yang lebih baik terhadap jadwal dan target kerja.",
    "Dari data produksi tiga bulan terakhir, dapat disimpulkan bahwa blok-blok yang mendapatkan perawatan intensif menunjukkan peningkatan hasil panen rata-rata 45% dibandingkan periode yang sama tahun lalu, membuktikan efektivitas pendekatan manajemen berbasis hasil.",
    "Rencana kerja empat bulan ke depan mencakup penyelesaian perbaikan jalan utama dan jalan pasar, penuntasan program pemberantasan gulma di seluruh blok, pelaksanaan pemupukan terjadwal, dan pemantauan intensif terhadap pertumbuhan pohon dan perkembangan tandan buah.",
    "Ketidakseimbangan regional dalam distribusi sumber daya dan perhatian manajemen menyebabkan disparitas produktivitas yang cukup besar antar blok. Area di wilayah utara menunjukkan performa lebih baik dibandingkan wilayah selatan yang memiliki akses jalan lebih sulit.",
    "Sistem monitoring harian yang baru telah diimplementasikan di semua blok prioritas, memungkinkan manajer lapangan untuk melacak kemajuan pekerjaan secara real-time dan mengambil tindakan korektif segera apabila ditemukan penyimpangan dari rencana yang telah ditetapkan.",
    "Evaluasi kualitas sisipan tahun 2021 menunjukkan tingkat keberhasilan hanya 62%, jauh di bawah standar minimal 80% yang ditetapkan perusahaan. Kegagalan sebagian besar disebabkan oleh kekurangan air pada masa kritis pertumbuhan awal dan serangan hama yang tidak terkendali.",
    "Dalam rapat koordinasi minggu lalu, manajemen memutuskan untuk mengalokasikan tambahan 15 unit alat berat untuk mempercepat perbaikan jalan di area prioritas, dengan estimasi penyelesaian 85% jaringan jalan utama sebelum akhir bulan depan sesuai target yang ditetapkan.",
    "Produksi bulan April mencapai 2.340 ton, meningkat 18% dibandingkan bulan Maret. Namun demikian, angka ini masih 23% di bawah target yang ditetapkan untuk periode yang sama. Penyebab utama gap adalah rendahnya produktivitas di blok-blok yang belum mendapat perawatan intensif.",
    "Tim agronomis menyimpulkan bahwa aplikasi pupuk NPK dengan dosis yang tepat dan waktu yang sesuai dapat meningkatkan produktivitas pohon dewasa hingga 30% dalam waktu enam bulan. Rekomendasi ini akan segera diimplementasikan di blok prioritas sebagai program percontohan.",
    "Permasalahan utama yang dihadapi adalah kurangnya koordinasi antara tim kontraktor eksternal dengan pengawas internal, menyebabkan tumpang tindih pekerjaan dan inefisiensi penggunaan sumber daya yang pada akhirnya berdampak pada keterlambatan penyelesaian target kerja.",
    "Laporan keuangan menunjukkan bahwa biaya operasional per ton produk meningkat 12% dibandingkan tahun lalu, terutama disebabkan oleh kenaikan biaya tenaga kerja dan bahan bakar. Tim manajemen sedang mengkaji opsi efisiensi untuk mengendalikan biaya tanpa mengurangi output.",
    "Standar operasional prosedur yang baru telah selesai disusun dan akan mulai diberlakukan mulai minggu depan. SOP ini mencakup prosedur pemeliharaan harian, standar kualitas pemupukan, protokol penanganan hama dan penyakit, serta mekanisme pelaporan dan eskalasi masalah.",
    "Dari total 102 blok yang ada, sebanyak 35 blok telah menyelesaikan siklus pemeliharaan pertama dengan hasil yang memuaskan, 45 blok masih dalam proses pengerjaan intensif, dan 22 blok sisanya baru akan dimulai setelah penyelesaian perbaikan akses jalan yang diperlukan.",
    "Kepala divisi tanaman menyampaikan bahwa kondisi curah hujan yang tidak menentu selama tiga bulan terakhir turut berkontribusi pada penurunan produktivitas. Program manajemen air melalui optimasi parit drainase dan kolam penampungan air menjadi prioritas infrastruktur berikutnya.",
    "Kami berkomitmen untuk mencapai target produksi tahunan meskipun menghadapi berbagai tantangan. Dengan implementasi rencana perbaikan yang komprehensif dan pengawasan ketat terhadap setiap tahap pelaksanaan, kami optimis target tersebut dapat dicapai dalam jangka waktu yang ditetapkan.",
    "Tinjauan mendalam terhadap data historis menunjukkan bahwa pola penurunan produksi yang terjadi saat ini telah berlangsung selama kurang lebih tiga tahun terakhir tanpa penanganan yang sistematis, sehingga diperlukan intervensi menyeluruh yang mencakup semua aspek manajemen kebun.",
    "Program pelatihan intensif untuk mandor dan pengawas lapangan dijadwalkan berlangsung selama dua minggu. Materi pelatihan mencakup teknik pemeliharaan tanaman terkini, manajemen tenaga kerja yang efektif, penggunaan aplikasi monitoring digital, dan prosedur pelaporan standar perusahaan.",
    "Berdasarkan analisis citra satelit terbaru, diperkirakan sekitar 30% area kebun masih memiliki tutupan gulma yang signifikan meskipun program pemberantasan telah berjalan selama dua bulan. Kondisi ini memerlukan evaluasi ulang terhadap metode dan intensitas pemberantasan yang dilakukan.",
    "Kami mengusulkan pembentukan tim task force khusus yang bertugas menangani blok-blok dengan permasalahan kompleks yang tidak dapat diselesaikan melalui prosedur normal. Tim ini akan terdiri dari tenaga ahli agronomi, manajer lapangan berpengalaman, dan dukungan teknis dari pusat.",
    "Investasi tambahan sebesar 2,5 miliar rupiah diusulkan untuk pengadaan 12 unit traktor rotary guna mempercepat program pemberantasan gulma. Dengan kapasitas kerja 5 hektar per hari per unit, target penyelesaian pemberantasan gulma di seluruh area dapat dimajukan dua bulan lebih awal.",
    "Sistem insentif berbasis kinerja untuk pekerja lapangan akan mulai diterapkan bulan depan sebagai upaya meningkatkan motivasi dan produktivitas kerja. Pekerja yang konsisten mencapai atau melampaui target harian akan mendapatkan bonus yang signifikan di luar upah pokok yang ditetapkan.",
    "Hasil pemeriksaan laboratorium terhadap sampel tanah dari berbagai blok menunjukkan bahwa pH tanah rata-rata berada pada angka 4,2 yang bersifat sangat asam. Kondisi ini memerlukan aplikasi kapur pertanian secara menyeluruh sebelum program pemupukan dapat memberikan hasil yang optimal.",
    "Koordinasi dengan pemerintah daerah terkait perizinan dan akses ke area yang berbatasan dengan kawasan hutan lindung telah mengalami kemajuan. Izin untuk membuka jalan akses baru telah diterima dan pekerjaan konstruksi dijadwalkan dimulai pada awal bulan depan sesuai rencana.",
    "Tim survei telah menyelesaikan pemetaan ulang seluruh area kebun menggunakan teknologi drone dan GPS presisi tinggi. Hasil pemetaan menunjukkan beberapa perbedaan signifikan antara data lama dengan kondisi aktual di lapangan yang akan menjadi dasar perencanaan operasional ke depan.",
    "Dalam rangka meningkatkan efisiensi distribusi pupuk, kami telah mendesain ulang rute logistik internal berdasarkan analisis optimasi terbaru. Estimasi penghematan bahan bakar mencapai 18% dan waktu distribusi berkurang rata-rata 35 menit per perjalanan dibandingkan rute yang lama.",
    "Laporan dari tim kesehatan dan keselamatan kerja menunjukkan penurunan signifikan insiden kecelakaan kerja sejak implementasi program safety awareness tiga bulan lalu. Angka kecelakaan turun dari rata-rata 3,2 insiden per bulan menjadi 0,8 insiden per bulan dalam periode evaluasi.",
    "Negosiasi dengan pemasok benih unggul telah menghasilkan kesepakatan pengadaan 8.000 bibit varietas terpilih untuk program peremajaan tahap pertama. Bibit akan mulai diterima dan disemai di pembibitan utama mulai bulan depan dengan kapasitas pembibitan saat ini yang mencukupi.",
    "Analisis komparatif antara blok yang mendapat perawatan intensif dengan blok kontrol yang belum mendapat perlakuan menunjukkan perbedaan produktivitas rata-rata sebesar 67%. Data ini memperkuat keyakinan manajemen bahwa investasi dalam program perbaikan ini akan memberikan return yang memadai.",
    "Kami mendapat laporan dari manajemen pusat bahwa target produksi regional telah direvisi naik sebesar 15% untuk tahun depan berdasarkan peningkatan permintaan pasar. Revisi target ini memerlukan percepatan program pemulihan dan ekspansi kapasitas yang sudah direncanakan sebelumnya.",
    "Program pemberdayaan masyarakat sekitar kebun yang melibatkan warga lokal sebagai mitra kerja telah berhasil merekrut 120 orang tenaga kerja baru. Inisiatif ini tidak hanya membantu menyelesaikan masalah kekurangan tenaga kerja tetapi juga meningkatkan hubungan baik dengan komunitas setempat.",
    "Tinjauan terhadap kontrak kerja sama dengan mitra strategis menunjukkan beberapa klausul yang perlu diperbarui untuk mencerminkan kondisi bisnis terkini dan melindungi kepentingan perusahaan dengan lebih baik. Tim legal sedang menyiapkan draft revisi untuk didiskusikan dalam pertemuan mendatang.",
    "Sistem manajemen air terpadu yang baru akan dibangun untuk mengoptimalkan penggunaan sumber air yang tersedia di area kebun. Sistem ini akan mencakup jaringan pipa distribusi utama, pompa bertenaga surya di titik-titik strategis, dan sistem monitoring debit air secara otomatis dan real-time.",
    "Rapat evaluasi bulanan ini menunjukkan bahwa dari 8 indikator kinerja utama yang ditetapkan, 3 indikator telah tercapai sesuai target, 3 indikator dalam tren positif namun belum mencapai target, dan 2 indikator masih memerlukan perhatian khusus dan intervensi segera dari manajemen.",
    "Kebijakan zero burning yang diterapkan perusahaan mengharuskan pengelolaan biomassa sisa panen dilakukan melalui pencacahan dan pengomposan. Untuk mendukung kebijakan ini, kami berencana pengadaan 4 unit mesin chipper tambahan yang dapat dioperasikan di seluruh area kebun secara efisien.",
    "Data cuaca dari stasiun meteorologi menunjukkan potensi El Nino yang dapat mempengaruhi pola curah hujan dalam 6 bulan ke depan. Antisipasi yang sudah dipersiapkan meliputi perluasan kapasitas penyimpanan air, penyesuaian jadwal pemupukan, dan penyiapan stok mulsa untuk konservasi kelembaban tanah.",
    "Peningkatan kapasitas pabrik pengolahan dari 60 ton per jam menjadi 80 ton per jam dijadwalkan selesai pada kuartal ketiga. Upgrade ini sangat penting untuk mengantisipasi peningkatan produksi dari program pemulihan kebun yang diperkirakan mulai berdampak signifikan pada semester kedua tahun depan.",
    "Tim riset dan pengembangan sedang mengevaluasi penerapan teknologi precision farming di kebun ini sebagai pilot project. Teknologi ini mencakup penggunaan sensor tanah otomatis, analisis data variabilitas lahan, dan rekomendasi pemupukan variabel yang disesuaikan dengan kebutuhan spesifik setiap blok.",
    "Masalah ketidakstabilan tim kontraktor yang telah berdampak negatif terhadap kemajuan program perbaikan kini mulai tertangani melalui mekanisme seleksi yang lebih ketat dan sistem kontrak berbasis kinerja. Kontraktor yang tidak memenuhi standar akan digantikan tanpa kompensasi tambahan.",
    "Program sertifikasi keberlanjutan yang ditargetkan selesai tahun depan memerlukan pemenuhan 47 kriteria yang mencakup aspek lingkungan, sosial, dan tata kelola. Saat ini kami telah memenuhi 31 kriteria dan sedang dalam proses pemenuhan 16 kriteria yang tersisa sesuai roadmap yang ada.",
    "Kami menerima keluhan dari komunitas petani mitra terkait keterlambatan pembayaran hasil panen selama dua siklus berturut-turut. Masalah ini telah diidentifikasi berasal dari keterlambatan proses verifikasi di level administrasi dan akan diselesaikan dalam 7 hari kerja ke depan tanpa penundaan.",
    "Dari hasil analisis terhadap 350 sampel daun yang diambil secara representatif dari berbagai blok, ditemukan bahwa 40% pohon mengalami defisiensi magnesium dan 25% mengalami defisiensi boron. Program koreksi nutrisi terpadu akan segera dilaksanakan berdasarkan temuan analisis ini.",
    "Strategi pemulihan terintegrasi yang kami usulkan terdiri dari tiga fase utama: fase stabilisasi dalam dua bulan pertama yang berfokus pada penanganan masalah mendesak, fase optimasi dalam dua bulan berikutnya untuk meningkatkan efisiensi operasional, dan fase pertumbuhan untuk mencapai target produksi.",
    "Kami telah berhasil mengidentifikasi dan mendokumentasikan praktik terbaik dari blok-blok yang menunjukkan performa unggul. Praktik-praktik ini akan distandarisasi dan disebarluaskan ke seluruh area melalui program pelatihan dan pendampingan intensif yang akan dimulai pada awal bulan depan.",
    "Evaluasi terhadap efektivitas program pemberantasan gulma menunjukkan bahwa metode mekanis menggunakan traktor rotary memberikan hasil yang lebih baik dan lebih tahan lama dibandingkan metode herbisida kimiawi, terutama di area dengan kemiringan lahan di bawah 15 derajat yang aman untuk alat berat.",
    "Laporan bulanan dari semua mandor menunjukkan bahwa hambatan terbesar dalam pelaksanaan program adalah kesulitan akses ke blok-blok terpencil akibat kondisi jalan yang rusak berat. Prioritas perbaikan jalan telah direvisi untuk mendahulukan akses ke blok-blok dengan potensi produksi tertinggi.",
    "Kami sangat mengapresiasi dukungan dan arahan dari manajemen pusat yang telah memungkinkan pelaksanaan program perbaikan ini berjalan dengan lebih sistematis. Dengan sumber daya yang telah dialokasikan dan koordinasi yang lebih baik, kami yakin target pemulihan akan tercapai sesuai jadwal yang ditetapkan.",
    "Hasil monitoring pertumbuhan vegetatif pada tanaman muda menunjukkan respons positif terhadap program pemupukan yang baru diterapkan. Tinggi pohon rata-rata meningkat 23% dibandingkan periode yang sama sebelum program dimulai, mengindikasikan perbaikan kondisi nutrisi yang signifikan di lapangan.",
    "Dalam rangka memperkuat kapasitas pengawasan lapangan, kami berencana menempatkan dua pengawas tambahan di setiap blok yang sedang dalam pemulihan intensif. Pengawas tambahan ini akan direkrut dari tenaga berpengalaman yang telah menyelesaikan program pelatihan sertifikasi agronomi perusahaan.",
    "Perkembangan harga komoditas di pasar global menunjukkan tren yang menguntungkan bagi perusahaan dalam jangka menengah. Harga referensi internasional saat ini berada 18% di atas rata-rata lima tahun terakhir, memberikan insentif yang kuat untuk mempercepat pemulihan kapasitas produksi.",
    "Tim manajemen lapangan menyampaikan bahwa moril dan semangat kerja tim telah meningkat signifikan sejak diimplementasikannya sistem insentif baru dan komunikasi yang lebih transparan dari manajemen mengenai target dan kemajuan program perbaikan yang sedang dilaksanakan bersama-sama.",
    "Kami merekomendasikan untuk mengalokasikan sumber daya tambahan ke 15 blok prioritas yang memiliki potensi pemulihan produksi tertinggi berdasarkan analisis data terkini. Konsentrasi upaya pada blok-blok ini diperkirakan akan memberikan dampak yang paling signifikan terhadap total produksi.",
    "Pertemuan dengan perwakilan dari lembaga keuangan mengenai fasilitas kredit untuk mendanai program ekspansi telah menghasilkan kesepakatan prinsip yang menggembirakan. Detail teknis dan persyaratan kredit akan difinalisasi dalam dua minggu ke depan sebelum perjanjian formal dapat ditandatangani.",
    "Analisis mendalam terhadap pola kegagalan sisipan selama tiga tahun terakhir mengidentifikasi tiga faktor penyebab utama: kualitas bibit yang tidak memenuhi standar akibat prosedur seleksi yang lemah, kekurangan air pada fase kritis pertumbuhan, dan persaingan yang tidak terkendali dengan gulma dan tanaman liar.",
    "Program rehabilitasi sosial untuk mantan pekerja yang terdampak restrukturisasi operasional sedang disiapkan bekerja sama dengan dinas ketenagakerjaan setempat. Program ini mencakup pelatihan keterampilan baru, bantuan modal usaha mikro, dan fasilitasi penempatan kerja di sektor lain yang relevan.",
    "Data dari sistem monitoring cuaca mikro yang dipasang di 12 titik strategis di seluruh area kebun memberikan informasi berharga mengenai variabilitas iklim lokal yang mempengaruhi keputusan operasional, terutama terkait jadwal pemupukan, irigasi, dan pelaksanaan operasi lapangan lainnya.",
    "Kami berhasil menegosiasikan harga yang lebih kompetitif untuk pengadaan pupuk NPK dan MgO untuk kebutuhan satu tahun ke depan, menghasilkan penghematan sekitar 8% dibandingkan harga kontrak tahun lalu. Penghematan ini akan dialihkan untuk memperluas cakupan program pemupukan ke blok-blok tambahan.",
    "Rencana jangka panjang lima tahun mencakup peningkatan total kapasitas produksi sebesar 40%, modernisasi infrastruktur pabrik pengolahan, implementasi sistem manajemen berbasis data digital di seluruh operasional, dan pengembangan program diversifikasi produk untuk mengurangi ketergantungan pada komoditas tunggal.",
    "Komitmen perusahaan terhadap standar lingkungan dan sosial yang tinggi menjadi keunggulan kompetitif yang semakin penting di pasar global. Sertifikasi keberlanjutan yang sedang kami kejar akan membuka akses ke segmen pasar premium yang bersedia membayar harga lebih tinggi untuk produk bersertifikat.",
    "Sebagai penutup presentasi, kami menegaskan kembali komitmen penuh tim manajemen lapangan untuk mencapai seluruh target yang telah ditetapkan dalam program pemulihan ini. Dukungan, arahan, dan pengawasan yang berkelanjutan dari manajemen pusat sangat diperlukan agar program ini dapat berjalan optimal.",
    "Diskusi panel hari ini telah menghasilkan beberapa kesepakatan penting yang akan menjadi dasar pelaksanaan program kerja bulan depan. Semua kepala divisi diminta untuk menerjemahkan kesepakatan ini ke dalam rencana aksi yang detail dan terukur dalam waktu tiga hari kerja ke depan tanpa penundaan.",
    "Pemantauan dan evaluasi yang ketat terhadap setiap tahap pelaksanaan program akan dilakukan secara rutin setiap dua minggu sekali. Laporan kemajuan akan disampaikan kepada manajemen pusat melalui sistem pelaporan digital yang terintegrasi, memungkinkan pengambilan keputusan yang lebih cepat dan akurat.",
    "Berdasarkan seluruh data dan analisis yang telah dipaparkan hari ini, dapat disimpulkan bahwa program pemulihan kebun Sanyuan berada pada jalur yang benar meskipun masih menghadapi berbagai tantangan operasional. Dengan konsistensi dalam implementasi dan pengawasan yang ketat, target pemulihan dalam empat bulan sangat dapat dicapai.",
    "Kami mengucapkan terima kasih kepada seluruh tim lapangan yang telah bekerja keras di bawah kondisi yang penuh tantangan. Dedikasi dan komitmen mereka adalah kunci keberhasilan program ini dan menjadi fondasi yang kuat bagi pemulihan produktivitas kebun secara menyeluruh dan berkelanjutan.",
    "Pelaksanaan audit internal yang dijadwalkan minggu depan akan mencakup semua aspek operasional mulai dari keuangan, sumber daya manusia, manajemen aset, hingga kepatuhan terhadap regulasi lingkungan. Hasil audit akan menjadi dasar perbaikan sistem dan prosedur yang komprehensif di semua lini operasional.",
    "Tim konsultan independen yang telah kami kontrak untuk melakukan penilaian teknis komprehensif akan menyampaikan laporan akhir mereka dalam dua minggu. Rekomendasi mereka akan diintegrasikan ke dalam rencana operasional jangka menengah yang sedang kami susun untuk periode dua tahun ke depan.",
    "Permasalahan sarana dan prasarana di camp karyawan yang menjadi sumber keluhan terus-menerus akan ditangani melalui program renovasi komprehensif yang dijadwalkan selesai dalam tiga bulan. Kondisi tempat tinggal yang layak adalah faktor penting dalam menjaga motivasi dan retensi tenaga kerja terampil.",
    "Kami menyambut positif rencana kunjungan tim audit eksternal dari kantor pusat yang dijadwalkan bulan depan. Kunjungan ini akan menjadi kesempatan untuk mendapatkan perspektif independen yang berharga dan memvalidasi kemajuan program perbaikan yang sedang kami laksanakan di seluruh area kebun.",
    "Dalam konteks perubahan iklim yang semakin nyata dampaknya, kami telah mulai mengintegrasikan aspek ketahanan iklim ke dalam semua perencanaan operasional jangka panjang. Langkah-langkah adaptasi yang direncanakan mencakup pengembangan varietas toleran kekeringan dan optimasi sistem manajemen air.",
    "Kami menyampaikan apresiasi yang tulus kepada manajemen pusat atas alokasi anggaran tambahan yang memungkinkan percepatan program pemulihan ini. Dengan dukungan finansial yang memadai dan arahan strategis yang jelas, kami yakin bahwa seluruh target yang ambisius ini dapat terwujud sesuai dengan timeline yang telah disepakati bersama.",
    "Salah satu pembelajaran penting dari program ini adalah pentingnya membangun sistem data dan pelaporan yang andal sejak awal sebagai fondasi pengambilan keputusan. Ke depannya, setiap program operasional besar akan dimulai dengan penetapan baseline data yang jelas dan mekanisme monitoring yang terstruktur dengan baik.",
    "Pada akhirnya, keberhasilan program pemulihan ini bukan hanya akan berdampak pada peningkatan produksi dan profitabilitas perusahaan, tetapi juga pada peningkatan kesejahteraan seluruh pemangku kepentingan termasuk karyawan, masyarakat sekitar, dan mitra bisnis yang telah lama menjadi bagian dari ekosistem bisnis kita.",
    "Kami memohon dukungan dari semua pihak untuk memastikan program ini berjalan lancar. Komitmen bersama dari seluruh jajaran manajemen, tenaga lapangan, mitra kontraktor, dan pemangku kepentingan lainnya adalah prasyarat mutlak bagi keberhasilan transformasi operasional yang sedang kami upayakan bersama.",
    "Sebagai langkah konkret pertama yang akan segera kami implementasikan adalah pembentukan war room manajemen yang beroperasi setiap hari untuk memantau kemajuan program secara real-time, mengidentifikasi hambatan yang muncul, dan mengkoordinasikan respons cepat dari semua fungsi yang terlibat dalam program pemulihan ini.",
    "Dari hasil konsultasi dengan tim ahli agronomi independen, diperoleh rekomendasi untuk memodifikasi formula pupuk yang digunakan agar lebih sesuai dengan karakteristik tanah di area ini. Perubahan formula ini diperkirakan akan meningkatkan efisiensi penyerapan nutrisi oleh tanaman hingga 25 persen.",
    "Penggunaan teknologi drone untuk pemetaan dan pemantauan kondisi tanaman dari udara telah memberikan wawasan yang sangat berharga bagi tim manajemen. Data yang dikumpulkan memungkinkan identifikasi area bermasalah secara lebih cepat dan akurat dibandingkan metode inspeksi manual yang selama ini digunakan.",
    "Kami telah melakukan benchmark terhadap praktik terbaik industri kelapa sawit di Malaysia dan Papua Nugini, dan menemukan bahwa tingkat produktivitas kami masih berada sekitar 30 persen di bawah rata-rata regional. Gap ini dapat ditutup dalam dua hingga tiga tahun dengan implementasi program perbaikan yang konsisten.",
    "Rencana pembangunan infrastruktur pengolahan limbah organik di area kebun akan memberikan manfaat ganda berupa pemanfaatan kompos sebagai pupuk organik tambahan dan pengurangan dampak lingkungan dari limbah tandan kosong kelapa sawit yang selama ini menjadi permasalahan pengelolaan sampah kebun.",
    "Sistem pembayaran upah berbasis elektronik yang akan diimplementasikan bulan depan dirancang untuk meningkatkan transparansi dan ketepatan waktu pembayaran kepada seluruh tenaga kerja. Sistem ini juga akan memudahkan pencatatan dan audit ketenagakerjaan yang diperlukan untuk pemenuhan standar sertifikasi berkelanjutan.",
    "Evaluasi menyeluruh terhadap efektivitas program pelatihan yang telah dilaksanakan selama enam bulan terakhir menunjukkan bahwa kompetensi teknis tenaga kerja meningkat rata-rata 40 persen. Namun demikian, masih diperlukan penguatan pada aspek keselamatan kerja dan penggunaan alat pelindung diri yang benar.",
    "Laporan inspeksi jembatan dan gorong-gorong di seluruh jaringan jalan kebun menunjukkan bahwa 35 persen infrastruktur tersebut memerlukan perbaikan segera untuk memastikan keamanan dan keandalan akses ke seluruh area produksi, terutama pada musim hujan ketika curah hujan tinggi dapat memperparah kondisi yang ada.",
    "Kami mendapat konfirmasi dari manajemen pusat bahwa usulan pengadaan alat mekanisasi tambahan telah disetujui dalam anggaran revisi kuartal ini. Pengiriman peralatan diperkirakan tiba dalam empat minggu dan akan segera dioperasikan setelah proses serah terima dan pelatihan operator selesai dilaksanakan.",
    "Sebagai kesimpulan dari seluruh rangkaian diskusi hari ini, kami menegaskan bahwa keberhasilan program pemulihan ini sangat bergantung pada konsistensi pelaksanaan, akuntabilitas di semua tingkatan manajemen, dan dukungan sumber daya yang memadai dari perusahaan induk secara berkesinambungan hingga target akhir tercapai.",
    "Perlu kami sampaikan bahwa kondisi cuaca ekstrem yang terjadi bulan lalu telah menyebabkan kerusakan infrastruktur jalan di beberapa titik kritis yang menghambat mobilisasi alat dan tenaga kerja secara signifikan, sehingga estimasi penyelesaian beberapa pekerjaan perlu direvisi dengan memperhitungkan waktu pemulihan infrastruktur tersebut.",
]

assert len(SENTENCES) == 100, f"Expected 100 sentences, got {len(SENTENCES)}"

# ---------- API call ----------
def call_api(model: str, text: str) -> tuple[float, float, int]:
    """Returns (latency_s, ttft_s, output_len)."""
    payload = json.dumps({
        "model": model,
        "stream": False,
        "max_tokens": MAX_TOKENS,
        "messages": [
            {"role": "system", "content": PROMPT_TEMPLATE},
            {"role": "user", "content": text},
        ],
    }).encode()

    req = urllib.request.Request(
        f"{BASE_URL}/chat/completions",
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
        },
    )

    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            body = resp.read()
        t1 = time.perf_counter()
        data = json.loads(body)
        content = data["choices"][0]["message"]["content"]
        return t1 - t0, t1 - t0, len(content)
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {body[:200]}") from e


def benchmark_model(model: str, sentences: list[str]) -> list[dict]:
    results = []
    errors = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(call_api, model, s): (i, s) for i, s in enumerate(sentences)}
        for future in concurrent.futures.as_completed(futures):
            i, s = futures[future]
            try:
                lat, ttft, out_len = future.result()
                results.append({
                    "idx": i,
                    "input_len": len(s),
                    "output_len": out_len,
                    "latency_s": lat,
                })
            except Exception as exc:
                errors += 1
                print(f"  [error] sentence {i}: {exc}")

    results.sort(key=lambda r: r["idx"])
    if errors:
        print(f"  {errors} requests failed")
    return results


def print_stats(label: str, results: list[dict]):
    lats = [r["latency_s"] for r in results]
    in_lens = [r["input_len"] for r in results]
    out_lens = [r["output_len"] for r in results]
    ratios = [r["output_len"] / r["input_len"] for r in results]

    lats_sorted = sorted(lats)
    n = len(lats_sorted)
    p50 = lats_sorted[n // 2]
    p90 = lats_sorted[int(n * 0.9)]
    p95 = lats_sorted[int(n * 0.95)]

    print(f"\n{'='*60}", flush=True)
    print(f"  Model: {label}", flush=True)
    print(f"  Samples: {n}", flush=True)
    print(f"  Latency (s):", flush=True)
    print(f"    mean = {statistics.mean(lats):.3f}  median(p50) = {p50:.3f}", flush=True)
    print(f"    p90  = {p90:.3f}  p95 = {p95:.3f}  max = {max(lats):.3f}", flush=True)
    print(f"    stdev= {statistics.stdev(lats):.3f}", flush=True)
    print(f"  Input  len: mean={statistics.mean(in_lens):.0f}  min={min(in_lens)}  max={max(in_lens)}", flush=True)
    print(f"  Output len: mean={statistics.mean(out_lens):.0f}  min={min(out_lens)}  max={max(out_lens)}", flush=True)
    print(f"  Compression ratio: mean={statistics.mean(ratios):.2f}x  (target={TARGET_RATIO:.2f}x)", flush=True)
    print(f"{'='*60}", flush=True)
    return {"mean": statistics.mean(lats), "p50": p50, "p90": p90, "p95": p95, "max": max(lats)}


def main():
    if not API_KEY:
        print("ERROR: OPENAI_API_KEY not set")
        return

    models = os.environ.get("MODELS", "").split(",") if os.environ.get("MODELS") else MODELS
    models = [m.strip() for m in models if m.strip()]

    print(f"Base URL  : {BASE_URL}")
    print(f"Models    : {len(models)}")
    print(f"Sentences : {len(SENTENCES)}  |  Concurrency: {CONCURRENCY}")
    print(f"Target ratio: {int(TARGET_RATIO*100)}%  |  Max tokens: {MAX_TOKENS}")

    sentences = SENTENCES[:]
    random.shuffle(sentences)

    all_stats = []
    for i, model in enumerate(models, 1):
        print(f"\n[{i}/{len(models)}] Benchmarking {model} ...", flush=True)
        results = benchmark_model(model, sentences)
        if not results:
            print(f"  SKIPPED (0 successful samples)", flush=True)
            continue
        stats = print_stats(model, results)
        all_stats.append((model, stats))

    # Summary table sorted by mean latency
    if len(all_stats) > 1:
        all_stats.sort(key=lambda x: x[1]["mean"])
        baseline_mean = next((s["mean"] for m, s in all_stats if "haiku" in m), all_stats[-1][1]["mean"])
        print(f"\n{'='*80}")
        print(f"  SUMMARY (sorted by mean latency, baseline = claude-haiku-4.5)")
        print(f"  {'Model':<45} {'mean':>6} {'p50':>6} {'p90':>6} {'p95':>6} {'vs baseline':>12}")
        print(f"  {'-'*45} {'-'*6} {'-'*6} {'-'*6} {'-'*6} {'-'*12}")
        for model, s in all_stats:
            diff_pct = (s["mean"] - baseline_mean) / baseline_mean * 100
            sign = "+" if diff_pct > 0 else ""
            marker = " ← baseline" if "haiku" in model else ""
            print(f"  {model:<45} {s['mean']:>6.2f} {s['p50']:>6.2f} {s['p90']:>6.2f} {s['p95']:>6.2f} {sign}{diff_pct:>+10.1f}%{marker}")
        print(f"{'='*80}")


if __name__ == "__main__":
    main()
