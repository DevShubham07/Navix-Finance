import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { MarketingHtml } from "@/components/site/marketing-html";
import { POSTS, POST_SLUGS } from "../../_content/blog-posts";

const BASE = "https://www.navixfinance.com";

// Pre-render every post at build time.
export function generateStaticParams() {
  return POST_SLUGS.map((slug) => ({ slug }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const post = POSTS[slug];
  if (!post) return {};
  const url = `/blog/${post.slug}`;
  return {
    title: `${post.title} — NAVIX`,
    description: post.description,
    alternates: { canonical: url },
    openGraph: {
      type: "article",
      url,
      title: post.title,
      description: post.description,
      publishedTime: post.datePublished,
    },
  };
}

export default async function BlogPostPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const post = POSTS[slug];
  if (!post) notFound();

  const articleLd = {
    "@context": "https://schema.org",
    "@type": "Article",
    headline: post.title,
    description: post.description,
    datePublished: post.datePublished,
    inLanguage: "en-IN",
    mainEntityOfPage: `${BASE}/blog/${post.slug}`,
    author: { "@id": `${BASE}/#organization` },
    publisher: { "@id": `${BASE}/#organization` },
    image: `${BASE}/opengraph-image`,
  };

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(articleLd) }}
      />
      <MarketingHtml html={post.html} />
    </>
  );
}
